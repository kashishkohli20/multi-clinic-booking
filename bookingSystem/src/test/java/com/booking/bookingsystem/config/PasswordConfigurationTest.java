package com.booking.bookingsystem.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(PasswordConfiguration.class)
class PasswordConfigurationTest {
    @Autowired PasswordEncoder encoder;

    @Test
    void encodedPasswordMatchesOriginalButNotIncorrectPassword() {
        String password = "A sample passphrase!";
        String hash = encoder.encode(password);

        assertNotEquals(password, hash);
        assertTrue(encoder.matches(password, hash));
        assertFalse(encoder.matches("An incorrect passphrase!", hash));
    }

    @Test
    void encodingSamePasswordTwiceUsesDifferentSalts() {
        String password = "A sample passphrase!";
        String firstHash = encoder.encode(password);
        String secondHash = encoder.encode(password);

        assertNotEquals(firstHash, secondHash);
        assertTrue(encoder.matches(password, firstHash));
        assertTrue(encoder.matches(password, secondHash));
    }

    @Test
    void passwordCaseAndSurroundingWhitespaceAreSignificant() {
        String password = "  My sample passphrase!  ";
        String hash = encoder.encode(password);

        assertTrue(encoder.matches(password, hash));
        assertFalse(encoder.matches(password.strip(), hash));
        assertFalse(encoder.matches("  my sample passphrase!  ", hash));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "\u00e9"})
    void acceptsPasswordsAtBcryptUtf8ByteLimit(String character) {
        // ASCII uses one UTF-8 byte; e-acute uses two.
        String password = character.repeat(character.equals("x") ? 72 : 36);
        assertTrue(encoder.matches(password, encoder.encode(password)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "\u00e9"})
    void rejectsEncodingBeyondBcryptUtf8ByteLimit(String character) {
        String password = character.repeat(character.equals("x") ? 73 : 37);
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(password));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingPasswordOrHashCannotMatch(String missing) {
        String password = "A sample passphrase!";
        String hash = encoder.encode(password);
        assertFalse(encoder.matches(missing, hash));
        assertFalse(encoder.matches(password, missing));
    }

    @Test
    void plaintextIsNotAcceptedAsAStoredHash() {
        assertFalse(encoder.matches("A sample passphrase!", "A sample passphrase!"));
    }
}
