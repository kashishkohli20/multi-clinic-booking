package com.booking.bookingsystem.service;

import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.util.EmailNormalizer;
import com.booking.bookingsystem.util.NameNormalizer;
import jakarta.validation.Validator;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountCreationServiceTest {
    @ParameterizedTest
    @CsvSource({"some_other_constraint,23505", "uq_app_user_email_lower,23502"})
    void unrelatedDatabaseFailuresAreNotReportedAsDuplicateAccounts(String constraint, String sqlState) {
        AppUserRepository users = mock(AppUserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        Validator validator = mock(Validator.class);
        when(validator.validate(any())).thenReturn(Set.of());
        when(encoder.encode(any())).thenReturn("test-encoded-value");
        var cause = new ConstraintViolationException("Database failure",
                new SQLException("Database failure", sqlState), constraint);
        var failure = new DataIntegrityViolationException("Database failure", cause);
        when(users.saveAndFlush(any())).thenThrow(failure);
        var service = new AccountCreationService(users, new NameNormalizer(), new EmailNormalizer(), encoder, validator);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.createAccount("Alice", "alice@example.com", "A sample passphrase!")));
    }
}
