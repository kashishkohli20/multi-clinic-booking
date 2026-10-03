package com.booking.bookingsystem.service;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.config.LoginSessionProperties;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.entity.LoginSession;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.repository.LoginSessionRepository;
import com.booking.bookingsystem.service.exception.SessionCreationNotAllowedException;
import com.booking.bookingsystem.service.exception.InvalidLoginSessionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"booking.auth.session.max-lifetime=90m", "booking.auth.session.idle-timeout=10m"})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
class LoginSessionServiceIT {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00.123456789Z");
    private final List<UUID> accountIds = new ArrayList<>();

    @Autowired LoginSessionService service;
    @Autowired LoginSessionRepository sessions;
    @Autowired AppUserRepository users;
    @Autowired LoginSessionProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean Clock clock;

    @BeforeEach
    void setTime() {
        when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void cleanup() {
        for (UUID id : accountIds) {
            jdbc.update("delete from login_session where user_id = ?", id);
            jdbc.update("delete from app_user where id = ?", id);
        }
    }

    @Test
    void createsCommittedSessionUsingClockAndConfiguredLifetime() {
        AppUser account = createUser(true);
        LoginSession created = service.createSession(account.getId());
        LoginSession stored = sessions.findById(created.getId()).orElseThrow();

        Instant expected = NOW.truncatedTo(ChronoUnit.MICROS);
        assertEquals(Duration.ofMinutes(90), properties.maxLifetime());
        assertEquals(account.getId(), stored.getAppUser().getId());
        assertEquals(expected, stored.getCreatedAt());
        assertEquals(expected, stored.getLastActivityAt());
        assertEquals(expected.plus(Duration.ofMinutes(90)), stored.getExpiresAt());
        assertEquals(created.getCreatedAt(), stored.getCreatedAt());
        assertEquals(created.getExpiresAt(), stored.getExpiresAt());
        assertNull(stored.getRevokedAt());
        assertEquals(0L, stored.getVersion());
        assertEquals(1, sessionCount(account.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "disabled", "deleted", "null"})
    void ineligibleAccountsCannotCreateSessions(String state) {
        UUID id;
        if (state.equals("missing")) {
            id = UUID.randomUUID();
        } else if (state.equals("null")) {
            id = null;
        } else {
            AppUser user = createUser(!state.equals("disabled"));
            id = user.getId();
            if (state.equals("deleted")) {
                users.deleteById(id);
            }
        }
        long before = sessions.count();
        var failure = assertThrows(SessionCreationNotAllowedException.class, () -> service.createSession(id));
        assertEquals("Unable to create a login session.", failure.getMessage());
        assertEquals(before, sessions.count());
    }

    @Test
    void repeatedCreationMakesIndependentSessionsWithoutExtendingPreviousExpiry() {
        AppUser account = createUser(true);
        LoginSession first = service.createSession(account.getId());
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        LoginSession second = service.createSession(account.getId());

        assertNotEquals(first.getId(), second.getId());
        assertEquals(2, sessionCount(account.getId()));
        assertEquals(first.getExpiresAt(), sessions.findById(first.getId()).orElseThrow().getExpiresAt());
        assertEquals(first.getExpiresAt().plusSeconds(60), second.getExpiresAt());
    }

    @Test
    void disabledAccountCannotCreateAnotherSessionAfterEarlierSuccess() {
        AppUser account = createUser(true);
        LoginSession first = service.createSession(account.getId());
        account.setEnabled(false);
        users.saveAndFlush(account);

        assertThrows(SessionCreationNotAllowedException.class, () -> service.createSession(account.getId()));
        assertEquals(1, sessionCount(account.getId()));
        assertTrue(sessions.findById(first.getId()).isPresent());
    }

    @Test
    void enclosingFailureRollsBackSessionCreation() {
        AppUser account = createUser(true);
        var transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            service.createSession(account.getId());
            assertEquals(1, sessionCount(account.getId()));
            throw new IllegalStateException("Simulated later token persistence failure");
        }));
        assertEquals(0, sessionCount(account.getId()));
        assertTrue(users.findById(account.getId()).isPresent());
    }

    @Test
    void repeatedEligibilityChecksDoNotRecordActivityOrChangeSession() {
        AppUser account = createUser(true);
        LoginSession session = service.createSession(account.getId());
        var before = sessionRow(session.getId());
        when(clock.instant()).thenReturn(session.getLastActivityAt().plusSeconds(60));
        assertEquals(session.getId(), service.requireValidSession(session.getId(), account.getId()).getId());
        when(clock.instant()).thenReturn(session.getLastActivityAt().plusSeconds(120));
        assertEquals(session.getId(), service.requireValidSession(session.getId(), account.getId()).getId());
        assertEquals(before, sessionRow(session.getId()));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void enforcesExactIdleDeadline(long nanosFromDeadline) {
        AppUser account = createUser(true);
        LoginSession session = service.createSession(account.getId());
        var before = sessionRow(session.getId());
        assertEquals(Duration.ofMinutes(10), properties.idleTimeout());
        Instant deadline = session.getLastActivityAt().plus(properties.idleTimeout());
        when(clock.instant()).thenReturn(deadline.plusNanos(nanosFromDeadline));

        if (nanosFromDeadline < 0) {
            assertEquals(session.getId(), service.requireValidSession(session.getId(), account.getId()).getId());
        } else {
            assertInvalid(session.getId(), account.getId());
        }
        assertEquals(before, sessionRow(session.getId()));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void absoluteExpiryRejectsSessionEvenWithRecentActivity(long nanosFromDeadline) {
        AppUser account = createUser(true);
        LoginSession session = service.createSession(account.getId());
        session.setLastActivityAt(session.getExpiresAt().minusSeconds(60));
        sessions.saveAndFlush(session);
        var before = sessionRow(session.getId());
        when(clock.instant()).thenReturn(session.getExpiresAt().plusNanos(nanosFromDeadline));

        if (nanosFromDeadline < 0) {
            assertEquals(session.getId(), service.requireValidSession(session.getId(), account.getId()).getId());
        } else {
            assertInvalid(session.getId(), account.getId());
        }
        assertEquals(before, sessionRow(session.getId()));
    }

    @Test
    void revokedSessionIsRejectedWhileAnotherSessionRemainsValid() {
        AppUser account = createUser(true);
        LoginSession revoked = service.createSession(account.getId());
        LoginSession other = service.createSession(account.getId());
        revoked.setRevokedAt(revoked.getCreatedAt());
        sessions.saveAndFlush(revoked);
        var before = sessionRow(revoked.getId());

        assertInvalid(revoked.getId(), account.getId());
        assertEquals(other.getId(), service.requireValidSession(other.getId(), account.getId()).getId());
        assertEquals(before, sessionRow(revoked.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-session", "missing-account", "other-account", "null-session", "null-account"})
    void rejectsMissingOrMismatchedIdentity(String scenario) {
        AppUser account = createUser(true);
        LoginSession session = service.createSession(account.getId());
        UUID sessionId = switch (scenario) {
            case "missing-session" -> UUID.randomUUID();
            case "null-session" -> null;
            default -> session.getId();
        };
        UUID accountId = switch (scenario) {
            case "missing-account" -> UUID.randomUUID();
            case "other-account" -> createUser(true).getId();
            case "null-account" -> null;
            default -> account.getId();
        };
        var before = sessionRow(session.getId());
        assertInvalid(sessionId, accountId);
        assertEquals(before, sessionRow(session.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "deleted"})
    void accountStateChangesInvalidateExistingSession(String state) {
        AppUser account = createUser(true);
        LoginSession session = service.createSession(account.getId());
        service.requireValidSession(session.getId(), account.getId());
        if (state.equals("deleted")) {
            users.deleteById(account.getId());
        } else {
            account.setEnabled(false);
            users.saveAndFlush(account);
        }
        var before = sessionRow(session.getId());
        assertInvalid(session.getId(), account.getId());
        assertEquals(before, sessionRow(session.getId()));
    }

    @Test
    void clockBeforeRecordedActivityDoesNotMakeSessionValid() {
        AppUser account = createUser(true);
        LoginSession session = service.createSession(account.getId());
        when(clock.instant()).thenReturn(session.getCreatedAt().minusNanos(1));
        assertInvalid(session.getId(), account.getId());
    }

    private void assertInvalid(UUID sessionId, UUID accountId) {
        var failure = assertThrows(InvalidLoginSessionException.class,
                () -> service.requireValidSession(sessionId, accountId));
        assertEquals("Login session is not valid.", failure.getMessage());
    }

    @Test
    void activityMovesIdleDeadlineButNeverAbsoluteDeadline() {
        AppUser account = createUser(true);
        LoginSession original = service.createSession(account.getId());
        Instant activity = original.getCreatedAt().plusSeconds(300);
        when(clock.instant()).thenReturn(activity.plusNanos(789));
        service.recordActivity(original.getId(), account.getId());
        LoginSession updated = sessions.findById(original.getId()).orElseThrow();
        assertEquals(activity, updated.getLastActivityAt());
        assertEquals(original.getExpiresAt(), updated.getExpiresAt());
        assertEquals(1L, updated.getVersion());
        when(clock.instant()).thenReturn(original.getCreatedAt().plus(properties.idleTimeout()));
        service.requireValidSession(original.getId(), account.getId());
        when(clock.instant()).thenReturn(activity.plus(properties.idleTimeout()));
        assertInvalid(original.getId(), account.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"idle", "absolute", "revoked", "disabled", "deleted", "wrong-account", "clock-backward"})
    void rejectedActivityDoesNotModifySession(String reason) {
        AppUser account = createUser(true);
        LoginSession session = service.createSession(account.getId());
        UUID caller = account.getId();
        switch (reason) {
            case "idle" -> when(clock.instant()).thenReturn(session.getLastActivityAt().plus(properties.idleTimeout()));
            case "absolute" -> {
                session.setLastActivityAt(session.getExpiresAt().minusSeconds(60));
                sessions.saveAndFlush(session);
                when(clock.instant()).thenReturn(session.getExpiresAt());
            }
            case "revoked" -> service.revokeSession(session.getId(), caller);
            case "disabled" -> {
                account.setEnabled(false);
                users.saveAndFlush(account);
            }
            case "deleted" -> users.deleteById(caller);
            case "wrong-account" -> caller = createUser(true).getId();
            case "clock-backward" -> when(clock.instant()).thenReturn(session.getCreatedAt().minusNanos(1));
            default -> throw new IllegalArgumentException(reason);
        }
        UUID callerId = caller;
        var before = sessionRow(session.getId());
        assertThrows(InvalidLoginSessionException.class, () -> service.recordActivity(session.getId(), callerId));
        assertEquals(before, sessionRow(session.getId()));
    }

    @Test
    void revocationIsPermanentAndRepeatedCallsPreserveFirstTimestamp() {
        AppUser account = createUser(true);
        LoginSession session = service.createSession(account.getId());
        LoginSession other = service.createSession(account.getId());
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        service.revokeSession(session.getId(), account.getId());
        var revoked = sessionRow(session.getId());
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        service.revokeSession(session.getId(), account.getId());
        assertEquals(revoked, sessionRow(session.getId()));
        assertEquals(NOW.plusSeconds(60).truncatedTo(ChronoUnit.MICROS),
                sessions.findById(session.getId()).orElseThrow().getRevokedAt());
        assertInvalid(session.getId(), account.getId());
        service.requireValidSession(other.getId(), account.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "disabled", "deleted", "clock-backward"})
    void revocationDoesNotRequireAnEligibleSession(String state) {
        AppUser account = createUser(true);
        LoginSession session = service.createSession(account.getId());
        switch (state) {
            case "expired" -> when(clock.instant()).thenReturn(session.getExpiresAt().plusSeconds(60));
            case "disabled" -> {
                account.setEnabled(false);
                users.saveAndFlush(account);
            }
            case "deleted" -> users.deleteById(account.getId());
            case "clock-backward" -> when(clock.instant()).thenReturn(session.getCreatedAt().minusSeconds(1));
            default -> throw new IllegalArgumentException(state);
        }
        service.revokeSession(session.getId(), account.getId());
        var row = sessionRow(session.getId());
        assertNotNull(row.get("revoked_at"));
        assertEquals(1L, row.get("version"));
        assertInvalid(session.getId(), account.getId());
    }

    @Test
    void unknownOrWrongIdentityCannotRevokeAnotherSession() {
        AppUser account = createUser(true);
        LoginSession session = service.createSession(account.getId());
        var before = sessionRow(session.getId());
        service.revokeSession(session.getId(), createUser(true).getId());
        service.revokeSession(UUID.randomUUID(), account.getId());
        service.revokeSession(null, account.getId());
        service.revokeSession(session.getId(), null);
        assertEquals(before, sessionRow(session.getId()));
    }

    @Test
    void revocationIsVisibleInsideSameTransactionAndRollsBackWithCaller() {
        AppUser account = createUser(true);
        LoginSession session = service.createSession(account.getId());
        var before = sessionRow(session.getId());
        var transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            service.requireValidSession(session.getId(), account.getId()); // Populate persistence context.
            service.revokeSession(session.getId(), account.getId());
            assertInvalid(session.getId(), account.getId());
            throw new IllegalStateException("Simulated later failure");
        }));
        assertEquals(before, sessionRow(session.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"activity-first", "revoke-first", "two-revocations", "two-activities", "expires-while-waiting"})
    void concurrentMutationsRespectDatabaseOrder(String scenario) throws Exception {
        AppUser account = createUser(true);
        LoginSession session = service.createSession(account.getId());
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                // Hold the row lock until the competing SQL is demonstrably waiting.
                jdbc.queryForObject("select id from login_session where id = ? for update", UUID.class, session.getId());
                if (scenario.equals("activity-first") || scenario.equals("two-activities")) {
                    service.recordActivity(session.getId(), account.getId());
                } else if (!scenario.equals("expires-while-waiting")) {
                    service.revokeSession(session.getId(), account.getId());
                }
                locked.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                if (scenario.equals("activity-first") || scenario.equals("two-revocations")) {
                    service.revokeSession(session.getId(), account.getId());
                } else if (scenario.equals("revoke-first") || scenario.equals("expires-while-waiting")) {
                    assertThrows(InvalidLoginSessionException.class,
                            () -> service.recordActivity(session.getId(), account.getId()));
                } else {
                    service.recordActivity(session.getId(), account.getId());
                }
            });
            awaitSessionLockWait();
            if (scenario.equals("expires-while-waiting")) {
                when(clock.instant()).thenReturn(session.getLastActivityAt().plus(properties.idleTimeout()));
            }
            release.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);

            LoginSession stored = sessions.findById(session.getId()).orElseThrow();
            assertEquals(session.getExpiresAt(), stored.getExpiresAt());
            if (scenario.equals("two-activities")) {
                assertNull(stored.getRevokedAt());
                assertEquals(NOW.plusSeconds(60).truncatedTo(ChronoUnit.MICROS), stored.getLastActivityAt());
            } else if (scenario.equals("expires-while-waiting")) {
                assertEquals(session.getLastActivityAt(), stored.getLastActivityAt());
                assertEquals(0L, stored.getVersion());
            } else {
                assertNotNull(stored.getRevokedAt());
                assertInvalid(session.getId(), account.getId());
                if (scenario.equals("two-revocations")) assertEquals(1L, stored.getVersion());
                if (scenario.equals("revoke-first")) assertEquals(session.getLastActivityAt(), stored.getLastActivityAt());
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private void awaitSessionLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and pid <> pg_backend_pid()
                      and wait_event_type = 'Lock' and query like '%login_session%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        fail("Competing session statement did not wait on the database lock");
    }

    private java.util.Map<String, Object> sessionRow(UUID sessionId) {
        return jdbc.queryForMap("select * from login_session where id = ?", sessionId);
    }

    private AppUser createUser(boolean enabled) {
        AppUser user = new AppUser();
        user.setName("Session test account");
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setEnabled(enabled);
        user = users.saveAndFlush(user);
        accountIds.add(user.getId());
        return user;
    }

    private int sessionCount(UUID accountId) {
        return jdbc.queryForObject("select count(*) from login_session where user_id = ?", Integer.class, accountId);
    }
}
