package com.booking.bookingsystem.service;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.entity.Patient;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.repository.PatientRepository;
import com.booking.bookingsystem.service.exception.AccountAlreadyExistsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
// No test transaction: assertions must see the service's committed/rolled-back state.
class PatientRegistrationServiceIT {
    private static final String PASSWORD = "A sample passphrase!";
    private final String email = UUID.randomUUID() + "@example.com";
    private final UUID existingPatientId = UUID.randomUUID();

    @Autowired PatientRegistrationService registration;
    @Autowired AccountCreationService accounts;
    @Autowired AppUserRepository users;
    @MockitoSpyBean PatientRepository patients;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from patient where user_id in (select id from app_user where lower(email) = ?) "
                + "or id = ?", email, existingPatientId);
        jdbc.update("delete from app_user where lower(email) = ?", email);
    }

    @Test
    void registersEnabledAccountAndLinkedPatientWithCleanedNames() {
        Patient created = registration.createPatient("  Alice Smith  ",
                "  " + email.toUpperCase(Locale.ROOT) + "  ", PASSWORD);
        Patient stored = patients.findById(created.getId()).orElseThrow();
        AppUser user = users.findById(stored.getAppUser().getId()).orElseThrow();

        assertEquals("Alice Smith", stored.getName());
        assertEquals("Alice Smith", user.getName());
        assertEquals(email, user.getEmail());
        assertTrue(user.isEnabled()); // Checks dirty checking actually reached PostgreSQL.
        assertTrue(encoder.matches(PASSWORD, user.getPasswordHash()));
        assertNotEquals(PASSWORD, user.getPasswordHash());
        assertEquals(1, accountCount());
        assertEquals(1, linkedPatientCount());
        assertEquals(0, jdbc.queryForObject("select count(*) from clinic_membership where user_id = ?",
                Integer.class, user.getId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from doctor where user_id = ?",
                Integer.class, user.getId()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsBlankNameBeforeCreatingAccount(String name) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> registration.createPatient(name, email, PASSWORD));
        assertEquals("Name must not be blank.", failure.getMessage());
        assertNoRegistration();
    }

    @Test
    void rejectsNameBeyondDatabaseLimit() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> registration.createPatient("a".repeat(256), email, PASSWORD));
        assertEquals("Name must not exceed 255 characters.", failure.getMessage());
        assertNoRegistration();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void accepts255UnicodeCharactersAfterTrimming(String character) {
        String name = character.repeat(255);
        Patient created = registration.createPatient(" " + name + " ", email, PASSWORD);
        assertEquals(name, patients.findById(created.getId()).orElseThrow().getName());
        assertEquals(name, users.findById(created.getAppUser().getId()).orElseThrow().getName());
    }

    @Test
    void invalidEmailDoesNotCreatePatient() {
        long before = patients.count();
        assertThrows(IllegalArgumentException.class,
                () -> registration.createPatient("Alice", "not-an-email", PASSWORD));
        assertEquals(before, patients.count());
        assertNoRegistration();
    }

    @Test
    void invalidPasswordDoesNotCreatePatient() {
        assertThrows(IllegalArgumentException.class,
                () -> registration.createPatient("Alice", email, "short"));
        assertNoRegistration();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingAccountCannotBeClaimedOrEnabledByRegistration(boolean deleted) {
        AppUser original = accounts.createAccount("Alice", email, PASSWORD);
        if (deleted) {
            users.deleteById(original.getId());
        }
        assertThrows(AccountAlreadyExistsException.class,
                () -> registration.createPatient("Someone else", email.toUpperCase(Locale.ROOT), PASSWORD));
        assertEquals(1, accountCount());
        assertEquals(0, linkedPatientCount());
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "select enabled from app_user where id = ?", Boolean.class, original.getId()));
        assertEquals(deleted, jdbc.queryForObject(
                "select deleted from app_user where id = ?", Boolean.class, original.getId()));
    }

    @Test
    void repeatedRegistrationDoesNotReplacePatientOrAccount() {
        Patient original = registration.createPatient("Alice", email, PASSWORD);
        assertThrows(AccountAlreadyExistsException.class,
                () -> registration.createPatient("Bob", email, "A different passphrase!"));
        assertEquals(1, accountCount());
        assertEquals(1, linkedPatientCount());
        assertEquals("Alice", patients.findById(original.getId()).orElseThrow().getName());
        AppUser user = users.findById(original.getAppUser().getId()).orElseThrow();
        assertEquals("Alice", user.getName());
        assertTrue(encoder.matches(PASSWORD, user.getPasswordHash()));
    }

    @Test
    void matchingNameDoesNotClaimUnlinkedExistingPatient() {
        jdbc.update("insert into patient (id, name) values (?, ?)", existingPatientId, "Alice");
        Patient created = registration.createPatient("Alice", email, PASSWORD);

        assertNotEquals(existingPatientId, created.getId());
        assertNull(jdbc.queryForObject("select user_id from patient where id = ?", UUID.class, existingPatientId));
        assertEquals("Alice", patients.findById(existingPatientId).orElseThrow().getName());
        assertEquals(1, linkedPatientCount());
    }

    @Test
    void patientSaveFailureRollsBackAlreadyInsertedAccount() {
        var failure = new DataIntegrityViolationException("Simulated patient persistence failure");
        doAnswer(invocation -> {
            assertEquals(1, accountCount()); // AccountCreationService already flushed its INSERT.
            throw failure;
        }).when(patients).save(any(Patient.class));

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> registration.createPatient("Alice", email, PASSWORD)));
        assertNoRegistration();
    }

    @Test
    void enclosingTransactionFailureRollsBackBothRecords() {
        var transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            registration.createPatient("Alice", email, PASSWORD);
            patients.flush();
            assertEquals(1, linkedPatientCount());
            throw new IllegalStateException("Simulated failure after registration");
        }));
        assertNoRegistration();
    }

    @Test
    void concurrentRegistrationCreatesExactlyOneAccountAndPatient() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> registerConcurrently(email, start));
            var second = executor.submit(() -> registerConcurrently(email.toUpperCase(Locale.ROOT), start));
            int successes = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(1, accountCount());
            assertEquals(1, linkedPatientCount());
            assertEquals(Boolean.TRUE, jdbc.queryForObject(
                    "select enabled from app_user where email = ?", Boolean.class, email));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private boolean registerConcurrently(String inputEmail, CyclicBarrier start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            registration.createPatient("Alice", inputEmail, PASSWORD);
            return true;
        } catch (AccountAlreadyExistsException expected) {
            return false;
        }
    }

    private int accountCount() {
        return jdbc.queryForObject("select count(*) from app_user where lower(email) = ?", Integer.class, email);
    }

    private int linkedPatientCount() {
        return jdbc.queryForObject("select count(*) from patient p join app_user u on p.user_id = u.id "
                + "where lower(u.email) = ?", Integer.class, email);
    }

    private void assertNoRegistration() {
        assertEquals(0, accountCount());
        assertEquals(0, linkedPatientCount());
    }
}
