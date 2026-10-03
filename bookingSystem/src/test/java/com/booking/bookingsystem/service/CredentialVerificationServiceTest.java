package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.service.exception.InvalidCredentialsException;
import com.booking.bookingsystem.util.EmailNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CredentialVerificationServiceTest {
    private static final String PASSWORD = "A sample passphrase!";
    private static final String STORED_HASH = "$2a$10$" + "a".repeat(53);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private CredentialVerificationService service;

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenReturn("dummy-hash");
        service = new CredentialVerificationService(users, new EmailNormalizer(), encoder);
        clearInvocations(encoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "disabled", "missing-hash", "malformed-hash"})
    void ineligibleAccountsStillDoPasswordWorkAndCannotAuthenticateWithDummyMatch(String state) {
        AppUser account = account();
        switch (state) {
            case "disabled" -> account.setEnabled(false);
            case "missing-hash" -> account.setPasswordHash(null);
            case "malformed-hash" -> account.setPasswordHash("legacy plaintext");
        }
        when(users.findByNormalizedEmail("alice@example.com"))
                .thenReturn(state.equals("missing") ? Optional.empty() : Optional.of(account));
        when(encoder.matches(PASSWORD, "dummy-hash")).thenReturn(true);

        var failure = assertThrows(InvalidCredentialsException.class,
                () -> service.verifyCredentials(" Alice@Example.com ", PASSWORD));

        assertEquals("Invalid email or password.", failure.getMessage());
        verify(encoder).matches(PASSWORD, "dummy-hash");
        verifyNoMoreInteractions(encoder);
        verify(users).findByNormalizedEmail("alice@example.com");
        verifyNoMoreInteractions(users);
    }

    @Test
    void verifiesTheUnchangedPasswordAgainstStoredHashWithoutReencoding() {
        AppUser account = account();
        when(users.findByNormalizedEmail("alice@example.com")).thenReturn(Optional.of(account));
        String password = "  A sample passphrase!  ";
        when(encoder.matches(password, STORED_HASH)).thenReturn(true);

        assertEquals(account.getId(), service.verifyCredentials("Alice@example.com", password));

        verify(encoder).matches(password, STORED_HASH);
        verifyNoMoreInteractions(encoder);
    }

    @Test
    void databaseFailureIsNotDisguisedAsBadCredentials() {
        var failure = new DataAccessResourceFailureException("Database unavailable");
        when(users.findByNormalizedEmail("alice@example.com")).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.verifyCredentials("alice@example.com", PASSWORD)));
        verifyNoInteractions(encoder);
    }

    @Test
    void oversizedPasswordsNeverReachBcryptOrDatabase() {
        for (String password : new String[]{"x".repeat(73), "é".repeat(37), "😀".repeat(19)}) {
            assertThrows(InvalidCredentialsException.class,
                    () -> service.verifyCredentials("alice@example.com", password));
        }
        verifyNoInteractions(users, encoder);
    }

    private AppUser account() {
        AppUser user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setEnabled(true);
        user.setPasswordHash(STORED_HASH);
        return user;
    }
}
