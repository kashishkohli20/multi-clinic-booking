package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.entity.LoginSession;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@Transactional
class LoginSessionIT {
    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00.123456Z");
    private static final Instant EXPIRES = CREATED.plusSeconds(8 * 3600);

    @Autowired LoginSessionRepository sessions;
    @Autowired AppUserRepository users;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void persistsAccountAndUtcTimestampsWithNoRevocationAndInitialVersion() {
        AppUser user = createUser();
        UUID id = createSession(user).getId();
        entityManager.clear();

        LoginSession stored = sessions.findById(id).orElseThrow();
        assertEquals(user.getId(), stored.getAppUser().getId());
        assertEquals(CREATED, stored.getCreatedAt());
        assertEquals(CREATED, stored.getLastActivityAt());
        assertEquals(EXPIRES, stored.getExpiresAt());
        assertNull(stored.getRevokedAt());
        assertEquals(0L, stored.getVersion());
    }

    @Test
    void accountCanHaveMultipleIndependentSessions() {
        AppUser user = createUser();
        LoginSession first = createSession(user);
        UUID secondId = createSession(user).getId();
        assertNotEquals(first.getId(), secondId);
        first.setRevokedAt(CREATED.plusSeconds(60));
        sessions.saveAndFlush(first);
        entityManager.clear();

        assertNotNull(sessions.findById(first.getId()).orElseThrow().getRevokedAt());
        assertNull(sessions.findById(secondId).orElseThrow().getRevokedAt());
        assertEquals(2, jdbc.queryForObject("select count(*) from login_session where user_id = ?",
                Integer.class, user.getId()));
    }

    @Test
    void activityAndRevocationPersistAndAdvanceVersionWithoutExtendingExpiry() {
        LoginSession session = createSession(createUser());
        session.setLastActivityAt(CREATED.plusSeconds(60));
        sessions.saveAndFlush(session);
        entityManager.clear();

        session = sessions.findById(session.getId()).orElseThrow();
        assertEquals(CREATED.plusSeconds(60), session.getLastActivityAt());
        assertEquals(1L, session.getVersion());
        // Revoking an already-expired session remains a valid audit event.
        session.setRevokedAt(EXPIRES.plusSeconds(60));
        sessions.saveAndFlush(session);
        entityManager.clear();

        LoginSession stored = sessions.findById(session.getId()).orElseThrow();
        assertEquals(EXPIRES.plusSeconds(60), stored.getRevokedAt());
        assertEquals(EXPIRES, stored.getExpiresAt());
        assertEquals(2L, stored.getVersion());
    }

    @Test
    void staleActivityUpdateCannotOverwriteRevocation() {
        LoginSession stale = createSession(createUser());
        entityManager.clear();
        LoginSession current = sessions.findById(stale.getId()).orElseThrow();
        current.setRevokedAt(CREATED.plusSeconds(60));
        sessions.saveAndFlush(current);
        entityManager.clear();

        stale.setLastActivityAt(CREATED.plusSeconds(120));
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> sessions.saveAndFlush(stale));
        // The failed merge did not overwrite the newer database row.
        assertEquals(Timestamp.from(CREATED.plusSeconds(60)), jdbc.queryForObject(
                "select revoked_at from login_session where id = ?", Timestamp.class, stale.getId()));
    }

    @Test
    void removingSessionDoesNotDeleteAccount() {
        AppUser user = createUser();
        LoginSession session = createSession(user);
        sessions.delete(session);
        sessions.flush();
        entityManager.clear();

        assertTrue(sessions.findById(session.getId()).isEmpty());
        assertTrue(users.findById(user.getId()).isPresent());
    }

    @Test
    void sqlInsertDefaultsToVersionZeroAndNoRevocation() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into login_session (id, user_id, created_at, last_activity_at, expires_at) "
                        + "values (?, ?, ?, ?, ?)", id, createUser().getId(), Timestamp.from(CREATED),
                Timestamp.from(CREATED), Timestamp.from(EXPIRES));
        assertEquals(0L, jdbc.queryForObject("select version from login_session where id = ?", Long.class, id));
        assertNull(jdbc.queryForObject("select revoked_at from login_session where id = ?", Timestamp.class, id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_id", "created_at", "last_activity_at", "expires_at", "version"})
    void databaseRejectsNullRequiredFields(String column) {
        UUID id = createSession(createUser()).getId();
        // Column names come solely from the fixed test cases; SQL bypasses Hibernate checks.
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update login_session set " + column + " = null where id = ?", id));
        assertSqlState("23502", failure);
    }

    @Test
    void databaseRejectsMissingAccount() {
        UUID id = createSession(createUser()).getId();
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update login_session set user_id = ? where id = ?", UUID.randomUUID(), id));
        assertSqlState("23503", failure);
    }

    static Stream<Arguments> invalidTimestamps() {
        return Stream.of(
                Arguments.of("expires_at", CREATED),
                Arguments.of("expires_at", CREATED.minusSeconds(1)),
                Arguments.of("last_activity_at", CREATED.minusSeconds(1)),
                Arguments.of("last_activity_at", EXPIRES),
                Arguments.of("last_activity_at", EXPIRES.plusSeconds(1)),
                Arguments.of("revoked_at", CREATED.minusSeconds(1)));
    }

    @ParameterizedTest
    @MethodSource("invalidTimestamps")
    void databaseRejectsInconsistentTimestamps(String column, Instant value) {
        UUID id = createSession(createUser()).getId();
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update login_session set " + column + " = ? where id = ?", Timestamp.from(value), id));
        assertSqlState("23514", failure);
    }

    @Test
    void databaseRejectsNegativeVersion() {
        UUID id = createSession(createUser()).getId();
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update login_session set version = -1 where id = ?", id));
        assertSqlState("23514", failure);
    }

    private AppUser createUser() {
        AppUser user = new AppUser();
        user.setName("Session test account");
        user.setEmail(UUID.randomUUID() + "@example.com");
        return users.saveAndFlush(user);
    }

    private LoginSession createSession(AppUser user) {
        LoginSession session = new LoginSession();
        session.setAppUser(user);
        session.setCreatedAt(CREATED);
        session.setLastActivityAt(CREATED);
        session.setExpiresAt(EXPIRES);
        return sessions.saveAndFlush(session);
    }

    private void assertSqlState(String expected, DataIntegrityViolationException failure) {
        SQLException cause = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertEquals(expected, cause.getSQLState());
    }
}
