package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.DoctorClinicAccessRequest;
import com.booking.bookingsystem.entity.DoctorClinicAccessRequest.Status;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@Transactional
class DoctorClinicAccessRequestIT {
    private static final Instant REQUESTED = Instant.parse("2030-01-01T00:00:00.123456Z");
    @Autowired DoctorClinicAccessRequestRepository requests;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void persistsPendingRequestWithUtcPrecisionWithoutGrantingMembership() {
        var fixture = fixture();
        var request = pending(fixture);
        entityManager.clear();
        var stored = requests.findById(request.getId()).orElseThrow();
        assertEquals(fixture.doctor(), stored.getDoctorId());
        assertEquals(fixture.clinic(), stored.getClinicId());
        assertEquals(REQUESTED, stored.getRequestedAt());
        assertEquals(Status.PENDING, stored.getStatus());
        assertEquals(0L, stored.getVersion());
        assertNull(stored.getDecidedAt());
        assertNull(stored.getDecidedById());
        assertTrue(requests.existsByDoctorIdAndClinicIdAndStatus(fixture.doctor(), fixture.clinic(), Status.PENDING));
        assertFalse(requests.existsByDoctorIdAndClinicIdAndStatus(fixture.doctor(), UUID.randomUUID(), Status.PENDING));
        assertFalse(requests.existsByDoctorIdAndClinicIdAndStatus(UUID.randomUUID(), fixture.clinic(), Status.PENDING));
        assertFalse(requests.existsByDoctorIdAndClinicIdAndStatus(fixture.doctor(), fixture.clinic(), Status.APPROVED));
        assertEquals(0, jdbc.queryForObject("select count(*) from clinic_membership where user_id=?", Integer.class, fixture.account()));
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"APPROVED", "REJECTED"})
    void retainsDecisionHistoryAlongsideANewPendingRequest(Status decision) {
        var fixture = fixture();
        var first = pending(fixture);
        decide(first, fixture.admin(), decision);
        requests.saveAndFlush(first); // Release the partial unique key before inserting another request.
        var second = pending(fixture);
        entityManager.clear();
        var stored = requests.findById(first.getId()).orElseThrow();
        assertEquals(decision, stored.getStatus());
        assertEquals(fixture.admin(), stored.getDecidedById());
        assertEquals(REQUESTED.plusSeconds(60), stored.getDecidedAt());
        assertEquals(1L, stored.getVersion());
        assertNotEquals(first.getId(), second.getId());
        assertEquals(2, count(fixture));
        // A stored decision alone is not a membership grant. Approval orchestration is a later service.
        assertEquals(0, jdbc.queryForObject("select count(*) from clinic_membership where user_id=?", Integer.class, fixture.account()));
    }

    @Test
    void databaseRejectsDuplicatePendingRequests() {
        var fixture = fixture();
        pending(fixture);
        var failure = assertThrows(DataIntegrityViolationException.class, () -> pending(fixture));
        assertSqlState("23505", failure);
        assertTrue(failure.getMostSpecificCause().getMessage().contains("uq_doctor_clinic_request_pending"));
    }

    @Test
    void differentClinicsAndDoctorsHaveIndependentPendingRequests() {
        var first = fixture();
        var second = fixture();
        pending(first);
        pending(new Fixture(first.account(), first.doctor(), second.clinic(), first.admin()));
        pending(new Fixture(second.account(), second.doctor(), first.clinic(), first.admin()));
        assertTrue(requests.existsByDoctorIdAndClinicIdAndStatus(first.doctor(), first.clinic(), Status.PENDING));
        assertTrue(requests.existsByDoctorIdAndClinicIdAndStatus(first.doctor(), second.clinic(), Status.PENDING));
        assertTrue(requests.existsByDoctorIdAndClinicIdAndStatus(second.doctor(), first.clinic(), Status.PENDING));
    }

    @Test
    void staleDecisionCannotOverwriteCommittedDecision() {
        var fixture = fixture();
        var stale = pending(fixture);
        entityManager.clear();
        var current = requests.findById(stale.getId()).orElseThrow();
        decide(current, fixture.admin(), Status.APPROVED);
        requests.saveAndFlush(current);
        entityManager.clear();
        decide(stale, fixture.admin(), Status.REJECTED);
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> requests.saveAndFlush(stale));
        assertEquals("APPROVED", jdbc.queryForObject("select status from doctor_clinic_access_request where id=?", String.class, stale.getId()));
    }

    @Test
    void ormUpdatesCannotReassignDoctorClinicOrRequestTime() {
        var fixture = fixture();
        var other = fixture();
        var request = pending(fixture);
        request.setDoctorId(other.doctor());
        request.setClinicId(other.clinic());
        request.setRequestedAt(REQUESTED.minusSeconds(30));
        decide(request, fixture.admin(), Status.REJECTED);
        requests.saveAndFlush(request);
        entityManager.clear();
        var stored = requests.findById(request.getId()).orElseThrow();
        assertEquals(fixture.doctor(), stored.getDoctorId());
        assertEquals(fixture.clinic(), stored.getClinicId());
        assertEquals(REQUESTED, stored.getRequestedAt());
        assertEquals(Status.REJECTED, stored.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"doctor", "clinic", "requester-account", "decider-account"})
    void historyRemainsReadableAfterReferencedRecordsAreSoftDeleted(String target) {
        var fixture = fixture();
        var request = pending(fixture);
        decide(request, fixture.admin(), Status.APPROVED);
        requests.saveAndFlush(request);
        switch (target) {
            case "doctor" -> jdbc.update("update doctor set deleted=true where id=?", fixture.doctor());
            case "clinic" -> jdbc.update("update clinic set deleted=true where id=?", fixture.clinic());
            case "requester-account" -> jdbc.update("update app_user set deleted=true, enabled=false where id=?", fixture.account());
            case "decider-account" -> jdbc.update("update app_user set deleted=true, enabled=false where id=?", fixture.admin());
        }
        entityManager.clear();
        var stored = requests.findById(request.getId()).orElseThrow();
        assertEquals(fixture.doctor(), stored.getDoctorId());
        assertEquals(fixture.clinic(), stored.getClinicId());
        assertEquals(fixture.admin(), stored.getDecidedById());
        assertEquals(Status.APPROVED, stored.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "doctor_id", "clinic_id", "status", "requested_at", "version"})
    void requiredColumnsCannotBeNull(String column) {
        var request = pending(fixture());
        assertSqlState("23502", assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update doctor_clinic_access_request set " + column + "=null where id=?", request.getId())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"doctor_id", "clinic_id", "decided_by_id"})
    void foreignKeysRejectUnknownRecords(String column) {
        var fixture = fixture();
        var request = pending(fixture);
        decide(request, fixture.admin(), Status.APPROVED);
        requests.saveAndFlush(request);
        assertSqlState("23503", assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update doctor_clinic_access_request set " + column + "=? where id=?", UUID.randomUUID(), request.getId())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-status", "pending-time", "pending-actor", "approved-missing-actor", "rejected-missing-time", "decision-before-request", "negative-version"})
    void databaseRejectsInconsistentDecisionState(String scenario) {
        var fixture = fixture();
        var request = pending(fixture);
        String update = switch (scenario) {
            case "unknown-status" -> "status='ACTIVE'";
            case "pending-time" -> "decided_at=requested_at";
            case "pending-actor" -> "decided_by_id='" + fixture.admin() + "'";
            case "approved-missing-actor" -> "status='APPROVED', decided_at=requested_at";
            case "rejected-missing-time" -> "status='REJECTED', decided_by_id='" + fixture.admin() + "'";
            case "decision-before-request" -> "status='APPROVED', decided_by_id='" + fixture.admin() + "', decided_at=requested_at-interval '1 microsecond'";
            case "negative-version" -> "version=-1";
            default -> throw new IllegalArgumentException(scenario);
        };
        assertSqlState("23514", assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update doctor_clinic_access_request set " + update + " where id=?", request.getId())));
    }

    @Test
    void sqlDefaultsToPendingAndVersionZeroAndAllowsDecisionAtRequestInstant() {
        var fixture = fixture();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into doctor_clinic_access_request (id,doctor_id,clinic_id,requested_at) values (?,?,?,?)",
                id, fixture.doctor(), fixture.clinic(), Timestamp.from(REQUESTED));
        var request = requests.findById(id).orElseThrow();
        assertEquals(Status.PENDING, request.getStatus());
        assertEquals(0L, request.getVersion());
        request.setStatus(Status.REJECTED);
        request.setDecidedById(fixture.admin());
        request.setDecidedAt(REQUESTED);
        requests.saveAndFlush(request);
        entityManager.clear();
        assertEquals(REQUESTED, requests.findById(id).orElseThrow().getDecidedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"doctor", "clinic", "app_user"})
    void physicalDeletionOfReferencedRecordsIsBlocked(String table) {
        var fixture = fixture();
        var request = pending(fixture);
        decide(request, fixture.admin(), Status.APPROVED);
        requests.saveAndFlush(request);
        UUID id = switch (table) {
            case "doctor" -> fixture.doctor();
            case "clinic" -> fixture.clinic();
            default -> fixture.admin();
        };
        assertSqlState("23503", assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("delete from " + table + " where id=?", id)));
    }

    @Test
    void deletingRequestDoesNotCascadeToProfilesAccountsOrClinic() {
        var fixture = fixture();
        var request = pending(fixture);
        requests.delete(request);
        requests.flush();
        assertEquals(0, count(fixture));
        assertEquals(1, jdbc.queryForObject("select count(*) from doctor where id=?", Integer.class, fixture.doctor()));
        assertEquals(1, jdbc.queryForObject("select count(*) from clinic where id=?", Integer.class, fixture.clinic()));
        assertEquals(1, jdbc.queryForObject("select count(*) from app_user where id=?", Integer.class, fixture.account()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentPendingInsertsCommitExactlyOneRequest() throws Exception {
        var fixture = fixture();
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                pending(fixture);
                inserted.countDown();
                try {
                    assertTrue(release.await(15, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(inserted.await(15, TimeUnit.SECONDS));
            var second = executor.submit(() -> assertThrows(DataIntegrityViolationException.class, () -> pending(fixture)));
            awaitInsertLockWait();
            release.countDown();
            first.get(15, TimeUnit.SECONDS);
            var conflict = second.get(15, TimeUnit.SECONDS);
            assertSqlState("23505", conflict);
            assertTrue(conflict.getMostSpecificCause().getMessage().contains("uq_doctor_clinic_request_pending"));
            assertEquals(1, count(fixture));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            jdbc.update("delete from doctor_clinic_access_request where doctor_id=?", fixture.doctor());
            jdbc.update("delete from doctor where id=?", fixture.doctor());
            jdbc.update("delete from clinic where id=?", fixture.clinic());
            jdbc.update("delete from app_user where id in (?,?)", fixture.account(), fixture.admin());
        }
    }

    private void awaitInsertLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            int waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity where datname=current_database()
                    and pid<>pg_backend_pid() and wait_event_type='Lock'
                    and query like '%doctor_clinic_access_request%'
                    """, Integer.class);
            if (waiting > 0) return;
            Thread.sleep(25);
        }
        fail("Competing insert did not wait on PostgreSQL's uniqueness check");
    }

    private Fixture fixture() {
        UUID account = account();
        UUID admin = account();
        UUID doctor = UUID.randomUUID();
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into doctor (id,user_id) values (?,?)", doctor, account);
        jdbc.update("insert into clinic (id) values (?)", clinic);
        return new Fixture(account, doctor, clinic, admin);
    }
    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app_user (id,email,enabled) values (?,?,true)", id, id + "@example.com");
        return id;
    }
    private DoctorClinicAccessRequest pending(Fixture fixture) {
        var request = new DoctorClinicAccessRequest();
        request.setDoctorId(fixture.doctor());
        request.setClinicId(fixture.clinic());
        request.setRequestedAt(REQUESTED);
        return requests.saveAndFlush(request);
    }
    private void decide(DoctorClinicAccessRequest request, UUID admin, Status status) {
        request.setStatus(status);
        request.setDecidedAt(REQUESTED.plusSeconds(60));
        request.setDecidedById(admin);
    }
    private int count(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from doctor_clinic_access_request where doctor_id=? and clinic_id=?",
                Integer.class, fixture.doctor(), fixture.clinic());
    }
    private void assertSqlState(String state, DataIntegrityViolationException failure) {
        assertEquals(state, assertInstanceOf(SQLException.class, failure.getMostSpecificCause()).getSQLState());
    }
    private record Fixture(UUID account, UUID doctor, UUID clinic, UUID admin) { }
}
