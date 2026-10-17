package com.booking.bookingsystem.service;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.service.exception.InvalidClinicAccessRequestException;
import com.booking.bookingsystem.service.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
class DoctorClinicAccessRequestServiceIT {
    @Autowired DoctorClinicAccessRequestService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final UUID account = UUID.randomUUID();
    private final UUID doctor = UUID.randomUUID();
    private final UUID clinic = UUID.randomUUID();

    @BeforeEach
    void setup() {
        jdbc.update("insert into app_user (id,email,enabled) values (?,?,true)", account, account + "@example.com");
        jdbc.update("insert into doctor (id,user_id) values (?,?)", doctor, account);
        jdbc.update("insert into clinic (id) values (?)", clinic);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from doctor_clinic_access_request where doctor_id=?", doctor);
        jdbc.update("delete from doctor where id=?", doctor);
        jdbc.update("delete from app_user where id=?", account);
        jdbc.update("delete from clinic where id=?", clinic);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null-account", "unknown-account", "disabled-account", "deleted-account", "unlinked-doctor", "deleted-doctor"})
    void internalServiceRequiresEligibleAccountAndLinkedDoctor(String state) {
        UUID identity = account;
        switch (state) {
            case "null-account" -> identity = null;
            case "unknown-account" -> identity = UUID.randomUUID();
            case "disabled-account" -> jdbc.update("update app_user set enabled=false where id=?", account);
            case "deleted-account" -> jdbc.update("update app_user set deleted=true where id=?", account);
            case "unlinked-doctor" -> jdbc.update("update doctor set user_id=null where id=?", doctor);
            case "deleted-doctor" -> jdbc.update("update doctor set deleted=true where id=?", doctor);
        }
        UUID caller = identity;
        assertThrows(AccessDeniedException.class, () -> service.submit(caller, clinic));
        assertEquals(0, requestCount());
    }

    @Test
    void missingClinicIdIsValidationFailure() {
        assertThrows(InvalidClinicAccessRequestException.class, () -> service.submit(account, null));
        assertEquals(0, requestCount());
    }

    @Test
    void enclosingFailureRollsBackFlushedRequest() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.submit(account, clinic);
            assertEquals(1, requestCount());
            throw new IllegalStateException("Outer operation failed");
        }));
        assertEquals(0, requestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "doctor", "clinic"})
    void eligibilityIsRecheckedAfterWaitingForConcurrentDeletion(String target) throws Exception {
        String table = target.equals("account") ? "app_user" : target;
        UUID id = switch (target) { case "account" -> account; case "doctor" -> doctor; default -> clinic; };
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var deletion = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("update " + table + " set deleted=true where id=?", id);
                changed.countDown();
                try {
                    assertTrue(release.await(15, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(changed.await(15, TimeUnit.SECONDS));
            var submission = executor.submit(() -> {
                if (target.equals("clinic")) assertThrows(ResourceNotFoundException.class, () -> service.submit(account, clinic));
                else assertThrows(AccessDeniedException.class, () -> service.submit(account, clinic));
            });
            awaitLockWait(table);
            release.countDown();
            deletion.get(15, TimeUnit.SECONDS);
            submission.get(15, TimeUnit.SECONDS);
            assertEquals(0, requestCount());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void awaitLockWait(String table) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            int count = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity where datname=current_database()
                    and pid<>pg_backend_pid() and wait_event_type='Lock' and query like ?
                    """, Integer.class, "%" + table + "%");
            if (count > 0) return;
            Thread.sleep(25);
        }
        fail("Submission did not wait on the database lock");
    }
    private int requestCount() {
        return jdbc.queryForObject("select count(*) from doctor_clinic_access_request where doctor_id=?", Integer.class, doctor);
    }
}
