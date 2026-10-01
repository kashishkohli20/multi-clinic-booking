package com.booking.bookingsystem.service;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.service.exception.AccountAlreadyExistsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
class AccountCreationServiceIT {
    private static final String PASSWORD = "A sample passphrase!";
    private final String email = UUID.randomUUID() + "@example.com";

    @Autowired AccountCreationService accounts;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from app_user where lower(email) = ?", email);
    }

    @Test
    void normalizesEmailAndPersistsOnlyHashInDisabledAccount() {
        String password = "  A sample passphrase!  ";
        AppUser created = accounts.createAccount("  " + email.toUpperCase(Locale.ROOT) + "  ", password);
        AppUser stored = users.findById(created.getId()).orElseThrow();

        assertEquals(email, stored.getEmail());
        assertFalse(stored.isEnabled());
        assertNotEquals(password, stored.getPasswordHash());
        assertTrue(encoder.matches(password, stored.getPasswordHash()));
        assertFalse(encoder.matches(password.strip(), stored.getPasswordHash()));
        assertFalse(encoder.matches("Another passphrase!", stored.getPasswordHash()));
        assertNotNull(stored.getCreatedAt());
        assertNotNull(stored.getUpdatedAt());
        assertEquals(0, jdbc.queryForObject("select count(*) from patient where user_id = ?",
                Integer.class, created.getId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from clinic_membership where user_id = ?",
                Integer.class, created.getId()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-an-email", "alice@", "alice @example.com"})
    void rejectsInvalidEmailWithoutSavingAccount(String invalidEmail) {
        long before = users.count();
        assertThrows(IllegalArgumentException.class, () -> accounts.createAccount(invalidEmail, PASSWORD));
        assertEquals(before, users.count());
    }

    @Test
    void rejectsEmailLongerThanColumnLimit() {
        String oversized = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63)
                + "." + "d".repeat(63) + ".com";
        long before = users.count();
        assertThrows(IllegalArgumentException.class, () -> accounts.createAccount(oversized, PASSWORD));
        assertEquals(before, users.count());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"                ", "short", "12345678901234", "😀😀😀😀😀😀😀😀"})
    void rejectsMissingBlankOrShortPassword(String password) {
        assertThrows(IllegalArgumentException.class, () -> accounts.createAccount(email, password));
        assertEquals(0, accountCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "é"})
    void rejectsPasswordBeyond72Utf8Bytes(String character) {
        String password = character.repeat(character.equals("x") ? 73 : 37);
        assertThrows(IllegalArgumentException.class, () -> accounts.createAccount(email, password));
        assertEquals(0, accountCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456789012345", "ééééééééééééééé", "😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀"})
    void accepts15UnicodeCodePoints(String password) {
        AppUser created = accounts.createAccount(email, password);
        assertTrue(encoder.matches(password, users.findById(created.getId()).orElseThrow().getPasswordHash()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "é"})
    void acceptsPasswordAt72Utf8Bytes(String character) {
        String password = character.repeat(character.equals("x") ? 72 : 36);
        AppUser created = accounts.createAccount(email, password);
        assertTrue(encoder.matches(password, created.getPasswordHash()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateEmailCannotReplaceExistingOrDeletedAccount(boolean deleted) {
        AppUser original = accounts.createAccount(email, PASSWORD);
        if (deleted) {
            users.deleteById(original.getId());
        }
        assertThrows(AccountAlreadyExistsException.class,
                () -> accounts.createAccount(" " + email.toUpperCase(Locale.ROOT) + " ", "Different passphrase!"));

        assertEquals(1, accountCount());
        assertEquals(original.getPasswordHash(), jdbc.queryForObject(
                "select password_hash from app_user where id = ?", String.class, original.getId()));
        assertEquals(deleted, jdbc.queryForObject(
                "select deleted from app_user where id = ?", Boolean.class, original.getId()));
    }

    @Test
    void accountCreationRollsBackWhenEnclosingTransactionFails() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            accounts.createAccount(email, PASSWORD);
            throw new IllegalStateException("Simulated later profile failure");
        }));
        assertEquals(0, accountCount());
    }

    @Test
    void concurrentDuplicateCreationCommitsExactlyOneAccount() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> createConcurrently(email, start));
            var second = executor.submit(() -> createConcurrently(email.toUpperCase(Locale.ROOT), start));
            int successes = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(1, accountCount());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private boolean createConcurrently(String inputEmail, CyclicBarrier start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            accounts.createAccount(inputEmail, PASSWORD);
            return true;
        } catch (AccountAlreadyExistsException expected) {
            return false;
        }
    }

    private int accountCount() {
        return jdbc.queryForObject("select count(*) from app_user where lower(email) = ?", Integer.class, email);
    }
}
