package com.booking.bookingsystem.service;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.service.exception.InvalidCredentialsException;
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

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
class CredentialVerificationServiceIT {
    private static final String PASSWORD = "  A sample passphrase!  ";
    private final String email = UUID.randomUUID() + "@example.com";

    @Autowired CredentialVerificationService service;
    @Autowired AccountCreationService accounts;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from login_session where user_id in (select id from app_user where lower(email) = ?)", email);
        jdbc.update("delete from app_user where lower(email) = ?", email);
    }

    @Test
    void verifiesRegisteredPasswordWithNormalizedEmailWithoutChangingDataOrCreatingSession() {
        AppUser user = accounts.createAccount("Alice", email, PASSWORD);
        user.setEnabled(true);
        users.saveAndFlush(user);
        var before = jdbc.queryForMap("select * from app_user where id = ?", user.getId());

        assertEquals(user.getId(), service.verifyCredentials(" \u2003" + email.toUpperCase(Locale.ROOT) + "\t", PASSWORD));
        assertEquals(user.getId(), service.verifyCredentials(email, PASSWORD));

        assertEquals(before, jdbc.queryForMap("select * from app_user where id = ?", user.getId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from login_session where user_id = ?", Integer.class, user.getId()));
    }

    @Test
    void findsOlderMixedCaseEmailUsingSameCaseInsensitiveIdentityRule() {
        AppUser user = createUser(PASSWORD);
        jdbc.update("update app_user set email = ? where id = ?", email.toUpperCase(Locale.ROOT), user.getId());
        assertEquals(user.getId(), service.verifyCredentials(email, PASSWORD));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-password", "trimmed-password", "changed-case", "disabled", "deleted", "unknown"})
    void failuresHaveSameMessageAndLeaveAccountUnchanged(String state) {
        AppUser user = createUser(PASSWORD);
        if (state.equals("disabled")) {
            jdbc.update("update app_user set enabled = false where id = ?", user.getId());
        } else if (state.equals("deleted")) {
            users.deleteById(user.getId());
        }
        String inputEmail = state.equals("unknown") ? "missing-" + email : email;
        String inputPassword = switch (state) {
            case "wrong-password" -> "Another passphrase!";
            case "trimmed-password" -> PASSWORD.strip();
            case "changed-case" -> PASSWORD.toLowerCase(Locale.ROOT);
            default -> PASSWORD;
        };
        var before = jdbc.queryForMap("select * from app_user where id = ?", user.getId());

        assertInvalid(inputEmail, inputPassword);

        assertEquals(before, jdbc.queryForMap("select * from app_user where id = ?", user.getId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from login_session where user_id = ?", Integer.class, user.getId()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "legacy plaintext", "$2a$03$invalid", "$2a$99$invalid", "{bcrypt}unsupported"})
    void missingOrUnusableLegacyHashesCannotAuthenticate(String hash) {
        AppUser user = createUser(PASSWORD);
        jdbc.update("update app_user set password_hash = ? where id = ?", hash, user.getId());
        assertInvalid(email, PASSWORD);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u2003", "not-an-email"})
    void invalidEmailUsesGenericFailure(String input) {
        assertInvalid(input, PASSWORD);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u2003"})
    void missingOrBlankPasswordUsesGenericFailure(String password) {
        assertInvalid(email, password);
    }

    @Test
    void oversizedEmailUsesGenericFailure() {
        assertInvalid("a".repeat(256) + "@example.com", PASSWORD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "é", "😀"})
    void acceptsExactly72Utf8BytesAndRejectsLongerPasswordWithSamePrefix(String character) {
        int repetitions = switch (character) {
            case "é" -> 36;
            case "😀" -> 18;
            default -> 72;
        };
        String password = character.repeat(repetitions);
        AppUser user = createUser(password);
        assertEquals(user.getId(), service.verifyCredentials(email, password));
        assertInvalid(email, password + "x");
    }

    @Test
    void loginDoesNotApplyNewRegistrationMinimumToExistingPassword() {
        AppUser user = createUser("OlderPassword!");
        assertEquals(user.getId(), service.verifyCredentials(email, "OlderPassword!"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "deleted"})
    void laterAccountStateChangeRejectsPreviouslyValidCredentials(String state) {
        AppUser user = createUser(PASSWORD);
        assertEquals(user.getId(), service.verifyCredentials(email, PASSWORD));
        if (state.equals("disabled")) {
            jdbc.update("update app_user set enabled = false where id = ?", user.getId());
        } else {
            users.deleteById(user.getId());
        }
        assertInvalid(email, PASSWORD);
    }

    private AppUser createUser(String password) {
        AppUser user = new AppUser();
        user.setName("Credential test account");
        user.setEmail(email);
        user.setPasswordHash(encoder.encode(password));
        user.setEnabled(true);
        return users.saveAndFlush(user);
    }

    private void assertInvalid(String inputEmail, String password) {
        var failure = assertThrows(InvalidCredentialsException.class,
                () -> service.verifyCredentials(inputEmail, password));
        assertEquals("Invalid email or password.", failure.getMessage());
    }
}
