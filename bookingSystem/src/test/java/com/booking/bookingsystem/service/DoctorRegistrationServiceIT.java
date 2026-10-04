package com.booking.bookingsystem.service;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.service.exception.AccountAlreadyExistsException;
import com.booking.bookingsystem.service.exception.InvalidRegistrationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
class DoctorRegistrationServiceIT {
    private static final String PASSWORD = "Doctor registration passphrase!";
    private final String email = UUID.randomUUID() + "@example.com";
    private final UUID legacyDoctor = UUID.randomUUID();
    @Autowired DoctorRegistrationService registration;
    @Autowired PatientRegistrationService patients;
    @Autowired AccountCreationService accounts;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean DoctorProfileService profiles;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from doctor where user_id in (select id from app_user where email=?) or id=?", email, legacyDoctor);
        jdbc.update("delete from patient where user_id in (select id from app_user where email=?)", email);
        jdbc.update("delete from app_user where email=?", email);
    }

    @Test
    void createsEnabledAccountAndDoctorOnlyUsingSharedValidationAndHashing() {
        var doctor = registration.register("  Dr Smith  ", " " + email.toUpperCase(Locale.ROOT) + " ", PASSWORD, "  Family medicine  ");
        var user = users.findById(doctor.getAppUser().getId()).orElseThrow();
        assertEquals("Dr Smith", user.getName());
        assertEquals("Dr Smith", doctor.getName());
        assertEquals("Family medicine", doctor.getDescription());
        assertEquals(email, user.getEmail());
        assertTrue(user.isEnabled());
        assertTrue(encoder.matches(PASSWORD, user.getPasswordHash()));
        assertEquals(1, accountCount());
        assertEquals(1, profileCount("doctor"));
        assertEquals(0, profileCount("patient"));
        assertEquals(0, profileCount("clinic_membership"));
        assertEquals(0, profileCount("login_session"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "email", "password", "description"})
    void invalidInputRollsBackRegistration(String field) {
        assertThrows(InvalidRegistrationException.class, () -> registration.register(
                field.equals("name") ? "\u2003" : "Dr Smith",
                field.equals("email") ? "invalid-email" : email,
                field.equals("password") ? "short" : PASSWORD,
                field.equals("description") ? "😀".repeat(256) : null));
        assertEquals(0, accountCount());
        assertEquals(0, profileCount("doctor"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "deleted", "patient", "doctor"})
    void existingAccountsCannotBeClaimedReenabledOrGivenAnotherProfile(String state) {
        UUID account;
        if (state.equals("patient")) account = patients.createPatient("Existing patient", email, PASSWORD).getAppUser().getId();
        else if (state.equals("doctor")) account = registration.register("Existing doctor", email, PASSWORD, null).getAppUser().getId();
        else {
            account = accounts.createAccount("Existing disabled user", email, PASSWORD).getId();
            if (state.equals("deleted")) users.deleteById(account);
        }
        var before = jdbc.queryForMap("select * from app_user where id=?", account);
        var doctorsBefore = jdbc.queryForList("select * from doctor where user_id=?", account);
        var patientsBefore = jdbc.queryForList("select * from patient where user_id=?", account);
        assertThrows(AccountAlreadyExistsException.class,
                () -> registration.register("New name", " " + email.toUpperCase(Locale.ROOT) + " ", "Different password phrase!", null));
        assertEquals(before, jdbc.queryForMap("select * from app_user where id=?", account));
        assertEquals(doctorsBefore, jdbc.queryForList("select * from doctor where user_id=?", account));
        assertEquals(patientsBefore, jdbc.queryForList("select * from patient where user_id=?", account));
        assertEquals(1, accountCount());
    }

    @Test
    void matchingNameDoesNotClaimAnUnlinkedLegacyDoctor() {
        jdbc.update("insert into doctor (id,name) values (?,?)", legacyDoctor, "Dr Smith");
        var created = registration.register("Dr Smith", email, PASSWORD, null);
        assertNotEquals(legacyDoctor, created.getId());
        assertNull(jdbc.queryForObject("select user_id from doctor where id=?", UUID.class, legacyDoctor));
    }

    @Test
    void failureAfterProfileFlushRollsBackNewAccountAndProfile() {
        var failure = new DataIntegrityViolationException("Simulated failure after profile creation");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertEquals(1, accountCount());
            assertEquals(1, profileCount("doctor"));
            throw failure;
        }).when(profiles).createProfile(any(), any(), any());
        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> registration.register("Dr Smith", email, PASSWORD, null)));
        assertEquals(0, accountCount());
        assertEquals(0, profileCount("doctor"));
    }

    @Test
    void registrationJoinsEnclosingTransaction() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            registration.register("Dr Smith", email, PASSWORD, null);
            assertEquals(1, profileCount("doctor"));
            throw new IllegalStateException("Outer operation failed");
        }));
        assertEquals(0, accountCount());
        assertEquals(0, profileCount("doctor"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentRegistrationAcrossEitherSignupRouteCreatesOneAccountAndOneProfile(boolean competingPatient) throws Exception {
        var start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> registerConcurrently(false, start));
            var second = executor.submit(() -> registerConcurrently(competingPatient, start));
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, accountCount());
            assertEquals(1, profileCount("doctor") + profileCount("patient"));
            assertEquals(0, profileCount("clinic_membership"));
            assertTrue(jdbc.queryForObject("select enabled from app_user where email=?", Boolean.class, email));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private boolean registerConcurrently(boolean patient, CyclicBarrier start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            if (patient) patients.createPatient("Patient", email.toUpperCase(Locale.ROOT), PASSWORD);
            else registration.register("Dr Smith", email, PASSWORD, null);
            return true;
        } catch (AccountAlreadyExistsException expected) {
            return false;
        }
    }

    private int accountCount() {
        return jdbc.queryForObject("select count(*) from app_user where email=?", Integer.class, email);
    }
    private int profileCount(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id in (select id from app_user where email=?)", Integer.class, email);
    }
}
