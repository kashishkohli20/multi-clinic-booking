package com.booking.bookingsystem.service;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.entity.LoginSession;
import com.booking.bookingsystem.entity.RefreshToken;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.repository.RefreshTokenRepository;
import com.booking.bookingsystem.security.RefreshTokenCodec;
import com.booking.bookingsystem.service.exception.InvalidRefreshTokenException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"booking.auth.session.max-lifetime=90m", "booking.auth.session.idle-timeout=10m"})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
// Deliberately no test transaction: verify actual commit/rollback boundaries.
class RefreshTokenServiceIT {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00.123456Z");
    private final List<UUID> accountIds = new ArrayList<>();
    @Autowired RefreshTokenService service;
    @Autowired LoginSessionService sessionService;
    @Autowired AppUserRepository users;
    @Autowired RefreshTokenCodec codec;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @MockitoSpyBean RefreshTokenRepository tokens;
    @MockitoBean Clock clock;

    @BeforeEach
    void setTime() {
        when(clock.instant()).thenReturn(NOW);
    }

    @AfterEach
    void cleanup() {
        for (UUID id : accountIds) {
            jdbc.update("delete from refresh_token where session_id in (select id from login_session where user_id = ?)", id);
            jdbc.update("delete from login_session where user_id = ?", id);
            jdbc.update("delete from app_user where id = ?", id);
        }
    }

    @Test
    void issuesHashOnlyAndRotatesWithoutExtendingEitherSessionDeadline() {
        LoginSession session = session();
        var originalSession = sessionRow(session.getId());
        var first = service.issueInitial(session.getId(), session.getAppUser().getId());
        assertEquals("IssuedToken[REDACTED]", first.toString());
        assertEquals(session.getExpiresAt(), first.expiresAt());
        var initial = tokens.findByTokenHash(codec.hash(first.value())).orElseThrow();
        assertNotEquals(first.value(), initial.getTokenHash());
        when(clock.instant()).thenReturn(NOW.plusSeconds(120).plusNanos(789));
        var next = service.rotate(first.value());

        assertNotEquals(first.value(), next.value());
        assertEquals(first.accountId(), next.accountId());
        assertEquals(first.sessionId(), next.sessionId());
        assertEquals(first.expiresAt(), next.expiresAt());
        assertEquals(NOW.plusSeconds(120), tokens.findByTokenHash(codec.hash(first.value())).orElseThrow().getConsumedAt());
        assertEquals(1L, tokens.findByTokenHash(codec.hash(first.value())).orElseThrow().getVersion());
        var successor = tokens.findByTokenHash(codec.hash(next.value())).orElseThrow();
        assertEquals(NOW.plusSeconds(120), successor.getCreatedAt());
        assertNull(successor.getConsumedAt());
        assertEquals(originalSession, sessionRow(session.getId()));
        assertEquals(2, count(session.getId()));
    }

    @Test
    void initialIssuanceJoinsLoginTransactionAndRollsBackWithIt() {
        UUID accountId = account();
        var tx = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            var session = sessionService.createSession(accountId);
            service.issueInitial(session.getId(), accountId);
            assertEquals(1, count(session.getId()));
            throw new IllegalStateException("Simulated login failure");
        }));
        assertEquals(0, jdbc.queryForObject("select count(*) from login_session where user_id = ?", Integer.class, accountId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-account", "missing-session", "null-session", "null-account", "disabled", "deleted", "revoked", "expired", "duplicate"})
    void initialIssuanceRejectsInvalidOrPreviouslyUsedSession(String state) {
        LoginSession session = session();
        UUID accountId = session.getAppUser().getId();
        switch (state) {
            case "disabled" -> jdbc.update("update app_user set enabled = false where id = ?", accountId);
            case "deleted" -> users.deleteById(accountId);
            case "revoked" -> sessionService.revokeSession(session.getId(), accountId);
            case "expired" -> when(clock.instant()).thenReturn(session.getExpiresAt());
            case "duplicate" -> service.issueInitial(session.getId(), accountId);
        }
        UUID suppliedSession = state.equals("null-session") ? null : state.equals("missing-session") ? UUID.randomUUID() : session.getId();
        UUID suppliedAccount = state.equals("null-account") ? null : state.equals("wrong-account") ? account() : accountId;
        var before = rows(session.getId());
        assertThrows(InvalidRefreshTokenException.class, () -> service.issueInitial(suppliedSession, suppliedAccount));
        assertEquals(before, rows(session.getId()));
    }

    @Test
    void replayCommitsRevocationBeforeRejectionAndLeavesOtherSessionsValid() {
        LoginSession session = session();
        UUID accountId = session.getAppUser().getId();
        var other = sessionService.createSession(accountId);
        var otherToken = service.issueInitial(other.getId(), accountId);
        var first = service.issueInitial(session.getId(), accountId);
        var successor = service.rotate(first.value());
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));

        assertInvalid(first.value());
        var revoked = sessionRow(session.getId());
        assertEquals(Timestamp.from(NOW.plusSeconds(60)), revoked.get("revoked_at"));
        assertInvalid(successor.value());
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        assertInvalid(first.value());
        assertEquals(revoked, sessionRow(session.getId()));
        assertEquals(2, count(session.getId()));
        assertNotNull(service.rotate(otherToken.value()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "deleted", "expired"})
    void replayStillRevokesWhenAccountOrSessionIsIneligible(String state) {
        LoginSession session = session();
        var first = service.issueInitial(session.getId(), session.getAppUser().getId());
        service.rotate(first.value());
        if (state.equals("disabled")) jdbc.update("update app_user set enabled = false where id = ?", session.getAppUser().getId());
        if (state.equals("deleted")) users.deleteById(session.getAppUser().getId());
        if (state.equals("expired")) when(clock.instant()).thenReturn(session.getExpiresAt().plusSeconds(1));
        assertInvalid(first.value());
        assertNotNull(sessionRow(session.getId()).get("revoked_at"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"idle", "absolute", "token-expired", "disabled", "deleted", "revoked", "clock-backward", "future-token", "token-outlives-session"})
    void invalidUnconsumedTokenDoesNotChangeDatabase(String reason) {
        LoginSession session = session();
        var token = service.issueInitial(session.getId(), session.getAppUser().getId());
        switch (reason) {
            case "idle" -> when(clock.instant()).thenReturn(NOW.plusSeconds(600));
            case "absolute" -> {
                jdbc.update("update login_session set last_activity_at = ? where id = ?", Timestamp.from(session.getExpiresAt().minusSeconds(1)), session.getId());
                when(clock.instant()).thenReturn(session.getExpiresAt());
            }
            case "token-expired" -> {
                jdbc.update("update refresh_token set expires_at = ? where session_id = ?", Timestamp.from(NOW.plusSeconds(30)), session.getId());
                when(clock.instant()).thenReturn(NOW.plusSeconds(30));
            }
            case "disabled" -> jdbc.update("update app_user set enabled = false where id = ?", session.getAppUser().getId());
            case "deleted" -> users.deleteById(session.getAppUser().getId());
            case "revoked" -> sessionService.revokeSession(session.getId(), session.getAppUser().getId());
            case "clock-backward" -> when(clock.instant()).thenReturn(NOW.minusNanos(1));
            case "future-token" -> jdbc.update("update refresh_token set created_at = ? where session_id = ?", Timestamp.from(NOW.plusSeconds(1)), session.getId());
            case "token-outlives-session" -> jdbc.update("update refresh_token set expires_at = ? where session_id = ?", Timestamp.from(session.getExpiresAt().plusSeconds(1)), session.getId());
        }
        var before = rows(session.getId());
        var sessionBefore = sessionRow(session.getId());
        assertInvalid(token.value());
        assertEquals(before, rows(session.getId()));
        assertEquals(sessionBefore, sessionRow(session.getId()));
    }

    @Test
    void acceptsJustBeforeIdleDeadlineAndRejectsUnknownTokenWithoutRevokingAnyone() {
        LoginSession session = session();
        var token = service.issueInitial(session.getId(), session.getAppUser().getId());
        assertInvalid(codec.generate());
        when(clock.instant()).thenReturn(NOW.plusSeconds(600).minusNanos(1));
        var next = service.rotate(token.value());
        when(clock.instant()).thenReturn(NOW.plusSeconds(600));
        assertInvalid(next.value()); // Refresh itself did not reset idle activity.
        assertNull(sessionRow(session.getId()).get("revoked_at"));
    }

    @Test
    void successorInsertFailureRollsBackConsumption() {
        LoginSession session = session();
        var first = service.issueInitial(session.getId(), session.getAppUser().getId());
        var before = rows(session.getId());
        var failure = new DataIntegrityViolationException("Simulated insert failure");
        doAnswer(invocation -> {
            assertNotNull(jdbc.queryForObject("select consumed_at from refresh_token where session_id = ?", Timestamp.class, session.getId()));
            throw failure;
        }).when(tokens).saveAndFlush(any(RefreshToken.class));
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.rotate(first.value())));
        assertEquals(before, rows(session.getId()));
    }

    @Test
    void rotationRejectsAmbientTransactionBeforeChangingAnything() {
        LoginSession session = session();
        var token = service.issueInitial(session.getId(), session.getAppUser().getId());
        var before = rows(session.getId());
        assertThrows(IllegalTransactionStateException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> service.rotate(token.value())));
        assertThrows(IllegalTransactionStateException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> service.rotateWithAccessToken(token.value())));
        assertEquals(before, rows(session.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate-refresh", "logout-first", "refresh-first", "idle-during-wait"})
    void competingOperationsAreSerializedAndCannotReviveSession(String scenario) throws Exception {
        LoginSession session = session();
        UUID id = session.getId();
        UUID accountId = session.getAppUser().getId();
        var original = service.issueInitial(id, accountId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(3);
        try {
            if (scenario.equals("refresh-first")) {
                doAnswer(invocation -> {
                    // The repository is an interface proxy; delegate its flush to the
                    // real transaction-bound EntityManager before holding the transaction.
                    entityManager.flush();
                    locked.countDown();
                    assertTrue(release.await(15, TimeUnit.SECONDS));
                    return null;
                }).when(tokens).flush();
            }
            var first = executor.submit(() -> {
                if (scenario.equals("refresh-first")) return service.rotate(original.value());
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.queryForObject("select id from login_session where id = ? for update", UUID.class, id);
                    if (scenario.equals("logout-first")) sessionService.revokeSession(id, accountId);
                    locked.countDown();
                    try {
                        assertTrue(release.await(15, TimeUnit.SECONDS));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                });
                return null;
            });
            if (!locked.await(15, TimeUnit.SECONDS)) {
                first.get(1, TimeUnit.SECONDS); // Surface worker failures rather than hiding them behind the latch.
                fail("First operation did not acquire its session lock");
            }
            var second = executor.submit(() -> {
                if (scenario.equals("refresh-first")) {
                    sessionService.revokeSession(id, accountId);
                    return null;
                }
                return rotateOrReject(original.value());
            });
            var third = scenario.equals("duplicate-refresh")
                    ? executor.submit(() -> rotateOrReject(original.value())) : null;
            awaitLockWait(scenario.equals("duplicate-refresh") ? 2 : 1);
            if (scenario.equals("idle-during-wait")) when(clock.instant()).thenReturn(NOW.plusSeconds(600));
            release.countDown();
            var firstResult = first.get(20, TimeUnit.SECONDS);
            var secondResult = second.get(20, TimeUnit.SECONDS);
            var thirdResult = third == null ? null : third.get(20, TimeUnit.SECONDS);

            if (scenario.equals("duplicate-refresh")) {
                assertEquals(1, (secondResult == null ? 0 : 1) + (thirdResult == null ? 0 : 1));
                assertEquals(2, count(id));
                assertInvalid((secondResult != null ? secondResult : thirdResult).value());
            } else if (scenario.equals("refresh-first")) {
                assertNotNull(firstResult);
                assertEquals(2, count(id));
                assertInvalid(firstResult.value());
            } else {
                assertNull(secondResult);
                assertEquals(1, count(id));
                assertNull(tokens.findByTokenHash(codec.hash(original.value())).orElseThrow().getConsumedAt());
            }
            if (scenario.equals("idle-during-wait")) assertNull(sessionRow(id).get("revoked_at"));
            else assertNotNull(sessionRow(id).get("revoked_at"));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    private void awaitLockWait(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            int waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity where datname = current_database()
                    and pid <> pg_backend_pid() and wait_event_type = 'Lock' and query like '%login_session%'
                    """, Integer.class);
            if (waiting >= expected) return;
            Thread.sleep(20);
        }
        fail("Competing operations did not wait on the session lock");
    }

    private RefreshTokenService.IssuedToken rotateOrReject(String token) {
        try { return service.rotate(token); }
        catch (InvalidRefreshTokenException expected) { return null; }
    }

    private void assertInvalid(String token) {
        var failure = assertThrows(InvalidRefreshTokenException.class, () -> service.rotate(token));
        assertEquals("Invalid refresh token.", failure.getMessage());
    }

    private UUID account() {
        AppUser user = new AppUser();
        user.setName("Refresh lifecycle test");
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setEnabled(true);
        UUID id = users.saveAndFlush(user).getId();
        accountIds.add(id);
        return id;
    }

    private LoginSession session() { return sessionService.createSession(account()); }
    private Map<String, Object> sessionRow(UUID id) { return jdbc.queryForMap("select * from login_session where id = ?", id); }
    private List<Map<String, Object>> rows(UUID id) { return jdbc.queryForList("select * from refresh_token where session_id = ? order by id", id); }
    private int count(UUID id) { return jdbc.queryForObject("select count(*) from refresh_token where session_id = ?", Integer.class, id); }
}
