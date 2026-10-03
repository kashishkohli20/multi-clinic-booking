package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.entity.LoginSession;
import com.booking.bookingsystem.entity.RefreshToken;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
class RefreshTokenIT {
    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00.123456Z");
    private static final Instant EXPIRES = CREATED.plusSeconds(8 * 3600);

    @Autowired RefreshTokenRepository tokens;
    @Autowired LoginSessionRepository sessions;
    @Autowired AppUserRepository users;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void persistsDigestSessionAndUtcTimestampsWithInitialVersion() {
        LoginSession session = createSession();
        RefreshToken token = createToken(session);
        entityManager.clear();

        RefreshToken stored = tokens.findByTokenHash(token.getTokenHash()).orElseThrow();
        assertEquals(token.getId(), stored.getId());
        assertEquals(session.getId(), stored.getLoginSession().getId());
        assertEquals(token.getTokenHash(), stored.getTokenHash());
        assertEquals(CREATED, stored.getCreatedAt());
        assertEquals(EXPIRES, stored.getExpiresAt());
        assertNull(stored.getConsumedAt());
        assertEquals(0L, stored.getVersion());
        assertTrue(tokens.findByTokenHash(hash()).isEmpty());
    }

    @Test
    void retainsConsumedHistoryAlongsideOneCurrentCredential() {
        LoginSession session = createSession();
        RefreshToken first = createToken(session);
        first.setConsumedAt(CREATED.plusSeconds(60));
        tokens.saveAndFlush(first); // Rotation must flush this before inserting a successor.
        RefreshToken second = createToken(session);
        second.setConsumedAt(CREATED.plusSeconds(120));
        tokens.saveAndFlush(second);
        RefreshToken current = createToken(session);
        entityManager.clear();

        RefreshToken storedFirst = tokens.findByTokenHash(first.getTokenHash()).orElseThrow();
        assertEquals(CREATED.plusSeconds(60), storedFirst.getConsumedAt());
        assertEquals(1L, storedFirst.getVersion());
        assertEquals(EXPIRES, storedFirst.getExpiresAt());
        assertEquals(CREATED.plusSeconds(120), tokens.findByTokenHash(second.getTokenHash()).orElseThrow().getConsumedAt());
        assertNull(tokens.findByTokenHash(current.getTokenHash()).orElseThrow().getConsumedAt());
        assertEquals(3, jdbc.queryForObject("select count(*) from refresh_token where session_id = ?",
                Integer.class, session.getId()));
    }

    @Test
    void separateSessionsCanEachHaveAnUnconsumedCredential() {
        LoginSession firstSession = createSession();
        LoginSession secondSession = new LoginSession();
        secondSession.setAppUser(firstSession.getAppUser());
        secondSession.setCreatedAt(CREATED);
        secondSession.setLastActivityAt(CREATED);
        secondSession.setExpiresAt(EXPIRES);
        sessions.saveAndFlush(secondSession);

        RefreshToken first = createToken(firstSession);
        RefreshToken second = createToken(secondSession);
        assertNotEquals(first.getId(), second.getId());
        assertEquals(2, jdbc.queryForObject("select count(*) from refresh_token where session_id in (?, ?)",
                Integer.class, firstSession.getId(), secondSession.getId()));
    }

    @Test
    void databaseRejectsTwoUnconsumedCredentialsForSameSession() {
        LoginSession session = createSession();
        createToken(session);
        var failure = assertThrows(DataIntegrityViolationException.class, () -> createToken(session));
        assertSqlState("23505", failure);
        assertTrue(failure.getMostSpecificCause().getMessage().contains("uq_refresh_token_unconsumed_session"));
    }

    @Test
    void databaseRejectsReusingConsumedHashEvenInAnotherSession() {
        RefreshToken consumed = createToken(createSession());
        consumed.setConsumedAt(CREATED.plusSeconds(60));
        tokens.saveAndFlush(consumed);
        RefreshToken other = createToken(createSession());

        var failure = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update refresh_token set token_hash = ? where id = ?", consumed.getTokenHash(), other.getId()));
        assertSqlState("23505", failure);
        assertTrue(failure.getMostSpecificCause().getMessage().contains("uq_refresh_token_hash"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoked", "disabled-account", "deleted-account", "expired"})
    void lookupRetainsHistoryRegardlessOfSessionEligibility(String state) {
        LoginSession session = createSession();
        RefreshToken token = createToken(session);
        token.setConsumedAt(CREATED.plusSeconds(60));
        tokens.saveAndFlush(token);
        switch (state) {
            case "revoked" -> {
                session.setRevokedAt(CREATED.plusSeconds(120));
                sessions.saveAndFlush(session);
            }
            case "disabled-account" -> {
                session.getAppUser().setEnabled(false);
                users.saveAndFlush(session.getAppUser());
            }
            case "deleted-account" -> {
                UUID accountId = session.getAppUser().getId();
                // Model deletion in a fresh persistence context, without managed sessions
                // still referencing the entity Hibernate is about to mark removed.
                entityManager.clear();
                users.deleteById(accountId);
            }
            case "expired" -> jdbc.update("""
                    update refresh_token set created_at = ?, consumed_at = ?, expires_at = ? where id = ?
                    """, Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")),
                    Timestamp.from(Instant.parse("2000-01-01T00:01:00Z")),
                    Timestamp.from(Instant.parse("2000-01-01T08:00:00Z")), token.getId());
        }
        entityManager.flush();
        entityManager.clear();

        RefreshToken stored = tokens.findByTokenHash(token.getTokenHash()).orElseThrow();
        assertEquals(token.getId(), stored.getId());
        assertEquals(session.getId(), stored.getLoginSession().getId());
        assertNotNull(stored.getConsumedAt());
    }

    @Test
    void staleEntityCannotUndoConsumption() {
        RefreshToken stale = createToken(createSession());
        entityManager.clear();
        RefreshToken current = tokens.findById(stale.getId()).orElseThrow();
        current.setConsumedAt(CREATED.plusSeconds(60));
        tokens.saveAndFlush(current);
        entityManager.clear();

        assertThrows(ObjectOptimisticLockingFailureException.class, () -> tokens.saveAndFlush(stale));
        assertEquals(Timestamp.from(CREATED.plusSeconds(60)), jdbc.queryForObject(
                "select consumed_at from refresh_token where id = ?", Timestamp.class, stale.getId()));
    }

    @Test
    void ormCannotReassignCredentialIdentityOrExtendExpiry() {
        LoginSession session = createSession();
        RefreshToken token = createToken(session);
        String originalHash = token.getTokenHash();
        token.setLoginSession(createSession());
        token.setTokenHash(hash());
        token.setCreatedAt(CREATED.plusSeconds(60));
        token.setExpiresAt(EXPIRES.plusSeconds(60));
        token.setConsumedAt(CREATED.plusSeconds(120));
        tokens.saveAndFlush(token);
        entityManager.clear();

        RefreshToken stored = tokens.findById(token.getId()).orElseThrow();
        assertEquals(session.getId(), stored.getLoginSession().getId());
        assertEquals(originalHash, stored.getTokenHash());
        assertEquals(CREATED, stored.getCreatedAt());
        assertEquals(EXPIRES, stored.getExpiresAt());
        assertEquals(CREATED.plusSeconds(120), stored.getConsumedAt());
    }

    @Test
    void deletingCredentialDoesNotCascadeToSessionOrAccount() {
        LoginSession session = createSession();
        UUID accountId = session.getAppUser().getId();
        RefreshToken token = createToken(session);
        tokens.delete(token);
        tokens.flush();
        entityManager.clear();

        assertTrue(tokens.findById(token.getId()).isEmpty());
        assertTrue(sessions.findById(session.getId()).isPresent());
        assertTrue(users.findById(accountId).isPresent());
    }

    @Test
    void parentCannotBeDeletedWhileCredentialHistoryExists() {
        LoginSession session = createSession();
        createToken(session);
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("delete from login_session where id = ?", session.getId()));
        assertSqlState("23503", failure);
    }

    @Test
    void sqlInsertDefaultsToVersionZeroAndNoConsumption() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into refresh_token (id, session_id, token_hash, created_at, expires_at)
                values (?, ?, ?, ?, ?)
                """, id, createSession().getId(), hash(), Timestamp.from(CREATED), Timestamp.from(EXPIRES));
        assertEquals(0L, jdbc.queryForObject("select version from refresh_token where id = ?", Long.class, id));
        assertNull(jdbc.queryForObject("select consumed_at from refresh_token where id = ?", Timestamp.class, id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "session_id", "token_hash", "created_at", "expires_at", "version"})
    void databaseRejectsNullRequiredFields(String column) {
        UUID id = createToken(createSession()).getId();
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update refresh_token set " + column + " = null where id = ?", id));
        assertSqlState("23502", failure);
    }

    @Test
    void databaseRejectsMissingSession() {
        UUID id = createToken(createSession()).getId();
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update refresh_token set session_id = ? where id = ?", UUID.randomUUID(), id));
        assertSqlState("23503", failure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "raw-token", "short", "uppercase", "non-hex", "newline"})
    void databaseRejectsInvalidHashRepresentation(String scenario) {
        String value = switch (scenario) {
            case "uppercase" -> "A".repeat(64);
            case "non-hex" -> "g".repeat(64);
            case "newline" -> "a".repeat(63) + "\n";
            default -> scenario;
        };
        UUID id = createToken(createSession()).getId();
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update refresh_token set token_hash = ? where id = ?", value, id));
        assertSqlState("23514", failure);
    }

    static Stream<Arguments> invalidTimestamps() {
        return Stream.of(
                Arguments.of("expires_at", CREATED),
                Arguments.of("expires_at", CREATED.minusSeconds(1)),
                Arguments.of("consumed_at", CREATED.minusSeconds(1)),
                Arguments.of("consumed_at", EXPIRES),
                Arguments.of("consumed_at", EXPIRES.plusSeconds(1)));
    }

    @ParameterizedTest
    @MethodSource("invalidTimestamps")
    void databaseRejectsInconsistentTimestamps(String column, Instant value) {
        UUID id = createToken(createSession()).getId();
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update refresh_token set " + column + " = ? where id = ?", Timestamp.from(value), id));
        assertSqlState("23514", failure);
    }

    @Test
    void databaseRejectsNegativeVersion() {
        UUID id = createToken(createSession()).getId();
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update refresh_token set version = -1 where id = ?", id));
        assertSqlState("23514", failure);
    }

    private LoginSession createSession() {
        AppUser user = new AppUser();
        user.setName("Refresh token test account");
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setEnabled(true);
        users.saveAndFlush(user);

        LoginSession session = new LoginSession();
        session.setAppUser(user);
        session.setCreatedAt(CREATED);
        session.setLastActivityAt(CREATED);
        session.setExpiresAt(EXPIRES);
        return sessions.saveAndFlush(session);
    }

    private RefreshToken createToken(LoginSession session) {
        RefreshToken token = new RefreshToken();
        token.setLoginSession(session);
        token.setTokenHash(hash());
        token.setCreatedAt(CREATED);
        token.setExpiresAt(EXPIRES);
        return tokens.saveAndFlush(token);
    }

    private String hash() {
        // Synthetic digests for persistence tests; raw credential generation belongs to the next chunk.
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private void assertSqlState(String expected, DataIntegrityViolationException failure) {
        SQLException cause = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertEquals(expected, cause.getSQLState());
    }
}
