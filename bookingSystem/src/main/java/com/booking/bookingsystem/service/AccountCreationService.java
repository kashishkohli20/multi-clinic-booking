package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.service.exception.AccountAlreadyExistsException;
import com.booking.bookingsystem.service.exception.InvalidRegistrationException;
import com.booking.bookingsystem.util.EmailNormalizer;
import com.booking.bookingsystem.util.NameNormalizer;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class AccountCreationService {
    private final AppUserRepository users;
    private final NameNormalizer nameNormalizer;
    private final EmailNormalizer emailNormalizer;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;

    /**
     * Creates a disabled account for use by a trusted registration/provisioning flow.
     * Joins the caller's transaction so later profile creation can roll back together.
     * The returned entity is internal and must not be exposed as an API response.
     */
    @Transactional
    public AppUser createAccount(String name, String email, String rawPassword) {
        String cleanedName = nameNormalizer.validateAndNormalizeName(name);
        if (email == null || email.isBlank()) {
            throw new InvalidRegistrationException("Email must not be blank.");
        }
        String normalizedEmail = emailNormalizer.normalize(email);
        if (!validator.validate(new EmailInput(normalizedEmail)).isEmpty()) {
            throw new InvalidRegistrationException("A valid email of at most 255 characters is required.");
        }
        validatePassword(rawPassword);

        AppUser account = new AppUser();
        account.setName(cleanedName);
        account.setEmail(normalizedEmail);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        account.setEnabled(false);
        try {
            // Flush here to translate this specific constraint, including concurrent inserts.
            return users.saveAndFlush(account);
        } catch (DataIntegrityViolationException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())
                        && "uq_app_user_email_lower".equals(violation.getConstraintName())) {
                    throw new AccountAlreadyExistsException(failure);
                }
            }
            throw failure;
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.isBlank()
                || password.codePointCount(0, password.length()) < 15) {
            throw new InvalidRegistrationException("Password must contain at least 15 characters and not be blank.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidRegistrationException("Password must not exceed 72 UTF-8 bytes.");
        }
    }

    private record EmailInput(@Email @Size(max = 255) String email) { }
}
