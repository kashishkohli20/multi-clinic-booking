package com.booking.bookingsystem.service;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.Doctor;
import com.booking.bookingsystem.repository.DoctorRepository;
import com.booking.bookingsystem.service.exception.DoctorProfileAlreadyExistsException;
import com.booking.bookingsystem.service.exception.InvalidRegistrationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
// No enclosing test transaction: verify committed state and rollback through the service proxy.
class DoctorProfileServiceIT {
    @Autowired DoctorProfileService profiles;
    @Autowired DoctorRepository doctors;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<UUID> accountIds = new ArrayList<>();
    private final UUID legacyDoctor = UUID.randomUUID();

    @AfterEach
    void cleanup() {
        jdbc.update("delete from doctor where id=?", legacyDoctor);
        for (UUID accountId : accountIds) {
            jdbc.update("delete from doctor where user_id=?", accountId);
            jdbc.update("delete from patient where user_id=?", accountId);
            jdbc.update("delete from app_user where id=?", accountId);
        }
    }

    @Test
    void createsOwnedProfileWithoutChangingAccountOrGrantingOtherCapabilities() {
        UUID account = account();
        var before = jdbc.queryForMap("select * from app_user where id=?", account);

        Doctor created = profiles.createProfile(account, "  Dr Smith\u2003", "\u2003Family medicine.  ");
        Doctor stored = doctors.findById(created.getId()).orElseThrow();
        assertEquals(account, stored.getAppUser().getId());
        assertEquals("Dr Smith", stored.getName());
        assertEquals("Family medicine.", stored.getDescription());
        assertNotNull(stored.getCreatedAt());
        assertNotNull(stored.getUpdatedAt());
        assertEquals(before, jdbc.queryForMap("select * from app_user where id=?", account));
        assertEquals(1, profileCount(account));
        assertEquals(0, jdbc.queryForObject("select count(*) from patient where user_id=?", Integer.class, account));
        assertEquals(0, jdbc.queryForObject("select count(*) from clinic_membership where user_id=?", Integer.class, account));
    }

    @Test
    void existingPatientCanAddDoctorCapabilityWithoutChangingPatient() {
        UUID account = account();
        UUID patient = UUID.randomUUID();
        jdbc.update("insert into patient (id,user_id,name,description) values (?,?,?,?)",
                patient, account, "Patient name", "Patient description");
        var before = jdbc.queryForMap("select * from patient where id=?", patient);
        profiles.createProfile(account, "Professional name", null);
        assertEquals(before, jdbc.queryForMap("select * from patient where id=?", patient));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void persists255CodePointsAfterTrimmingAndRejects256(String character) {
        UUID account = account();
        String maximum = character.repeat(255);
        Doctor created = profiles.createProfile(account, " " + maximum + " ", " " + maximum + " ");
        Doctor stored = doctors.findById(created.getId()).orElseThrow();
        assertEquals(maximum, stored.getName());
        assertEquals(maximum, stored.getDescription());

        UUID other = account();
        assertThrows(InvalidRegistrationException.class,
                () -> profiles.createProfile(other, character.repeat(256), null));
        assertThrows(InvalidRegistrationException.class,
                () -> profiles.createProfile(other, "Dr Smith", character.repeat(256)));
        assertEquals(0, profileCount(other));
    }

    @Test
    void storesBlankDescriptionAsNullAndRejectsBlankNameWithoutWrites() {
        UUID account = account();
        assertThrows(InvalidRegistrationException.class, () -> profiles.createProfile(account, "\u2003", null));
        assertEquals(0, profileCount(account));
        Doctor doctor = profiles.createProfile(account, "Dr Smith", "\u2003");
        assertNull(doctors.findById(doctor.getId()).orElseThrow().getDescription());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "disabled", "deleted", "null"})
    void rejectsIneligibleAccountsWithoutCreatingProfiles(String state) {
        UUID account = account();
        if (state.equals("disabled")) jdbc.update("update app_user set enabled=false where id=?", account);
        if (state.equals("deleted")) jdbc.update("update app_user set deleted=true where id=?", account);
        UUID caller = switch (state) {
            case "missing" -> UUID.randomUUID();
            case "null" -> null;
            default -> account;
        };
        var before = jdbc.queryForMap("select * from app_user where id=?", account);
        assertThrows(AccessDeniedException.class, () -> profiles.createProfile(caller, "Dr Smith", null));
        assertEquals(0, profileCount(account));
        assertEquals(before, jdbc.queryForMap("select * from app_user where id=?", account));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingProfileIncludingSoftDeletedCannotBeOverwrittenOrRestored(boolean deleted) {
        UUID account = account();
        Doctor original = profiles.createProfile(account, "Original name", "Original biography");
        if (deleted) doctors.deleteById(original.getId());
        var before = jdbc.queryForMap("select * from doctor where id=?", original.getId());
        assertThrows(DoctorProfileAlreadyExistsException.class,
                () -> profiles.createProfile(account, "Replacement", "Replacement biography"));
        assertEquals(1, profileCount(account));
        assertEquals(before, jdbc.queryForMap("select * from doctor where id=?", original.getId()));
    }

    @Test
    void matchingNamesNeverClaimLegacyProfileOrAnotherAccountsProfile() {
        jdbc.update("insert into doctor (id,name) values (?,?)", legacyDoctor, "Dr Smith");
        UUID firstAccount = account();
        UUID secondAccount = account();
        Doctor first = profiles.createProfile(firstAccount, "Dr Smith", null);
        Doctor second = profiles.createProfile(secondAccount, "Dr Smith", null);
        assertNotEquals(first.getId(), second.getId());
        assertNotEquals(legacyDoctor, first.getId());
        assertNotEquals(legacyDoctor, second.getId());
        assertNull(jdbc.queryForObject("select user_id from doctor where id=?", UUID.class, legacyDoctor));
        assertEquals(firstAccount, doctors.findById(first.getId()).orElseThrow().getAppUser().getId());
        assertEquals(secondAccount, doctors.findById(second.getId()).orElseThrow().getAppUser().getId());
    }

    @Test
    void callerFailureRollsBackFlushedProfileAndLeavesAccountUnchanged() {
        UUID account = account();
        var before = jdbc.queryForMap("select * from app_user where id=?", account);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            profiles.createProfile(account, "Dr Smith", null);
            assertEquals(1, profileCount(account));
            throw new IllegalStateException("Simulated enclosing operation failure");
        }));
        assertEquals(0, profileCount(account));
        assertEquals(before, jdbc.queryForMap("select * from app_user where id=?", account));
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "disable-first", "delete-first", "profile-first"})
    void concurrentCreationAndAccountChangesRespectDatabaseLockOrder(String scenario) throws Exception {
        UUID account = account();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("select id from app_user where id=? for update", UUID.class, account);
                if (scenario.equals("disable-first")) {
                    jdbc.update("update app_user set enabled=false where id=?", account);
                } else if (scenario.equals("delete-first")) {
                    jdbc.update("update app_user set deleted=true where id=?", account);
                } else {
                    profiles.createProfile(account, "First profile", null);
                }
                locked.countDown();
                try {
                    assertTrue(release.await(15, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }));
            assertTrue(locked.await(15, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                if (scenario.equals("profile-first")) {
                    jdbc.update("update app_user set enabled=false where id=?", account);
                } else if (scenario.equals("duplicate")) {
                    assertThrows(DoctorProfileAlreadyExistsException.class,
                            () -> profiles.createProfile(account, "Second profile", null));
                } else {
                    assertThrows(AccessDeniedException.class,
                            () -> profiles.createProfile(account, "Second profile", null));
                }
            });
            awaitAccountLockWait();
            release.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);

            int expectedProfiles = scenario.equals("duplicate") || scenario.equals("profile-first") ? 1 : 0;
            assertEquals(expectedProfiles, profileCount(account));
            if (expectedProfiles == 1) {
                assertEquals("First profile", jdbc.queryForObject("select name from doctor where user_id=?", String.class, account));
            }
            assertEquals(!scenario.equals("disable-first") && !scenario.equals("profile-first"),
                    jdbc.queryForObject("select enabled from app_user where id=?", Boolean.class, account));
            assertEquals(scenario.equals("delete-first"),
                    jdbc.queryForObject("select deleted from app_user where id=?", Boolean.class, account));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private void awaitAccountLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname=current_database() and pid<>pg_backend_pid()
                      and wait_event_type='Lock' and query like '%app_user%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(25);
        }
        fail("Competing operation did not wait on the account row lock");
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app_user (id,name,email,password_hash,enabled,created_at,updated_at) "
                        + "values (?,?,?, ?,true,now(),now())", id, "Account name", id + "@example.com", "test-only-unused-hash");
        accountIds.add(id);
        return id;
    }

    private int profileCount(UUID account) {
        return jdbc.queryForObject("select count(*) from doctor where user_id=?", Integer.class, account);
    }
}
