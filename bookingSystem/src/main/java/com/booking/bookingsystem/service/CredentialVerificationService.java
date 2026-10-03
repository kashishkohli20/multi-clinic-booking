package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.service.exception.InvalidCredentialsException;
import com.booking.bookingsystem.util.EmailNormalizer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CredentialVerificationService {
    // PasswordConfiguration currently writes BCrypt hashes only. Reject legacy
    // plaintext/unsupported formats instead of attempting to interpret or migrate them.
    private static final Pattern BCRYPT_HASH = Pattern.compile(
            "\\A\\$2[aby]?\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}\\z");

    private final AppUserRepository users;
    private final EmailNormalizer emailNormalizer;
    private final PasswordEncoder passwordEncoder;
    private final String dummyHash;

    public CredentialVerificationService(AppUserRepository users, EmailNormalizer emailNormalizer,
                                         PasswordEncoder passwordEncoder) {
        this.users = users;
        this.emailNormalizer = emailNormalizer;
        this.passwordEncoder = passwordEncoder;
        // Generated once using the configured cost; this value can never authenticate anyone.
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Verifies credentials without creating sessions, granting roles, or returning an entity.
     * A successful result is only a point-in-time check; session creation and subsequent
     * requests must still check account eligibility.
     */
    @Transactional(readOnly = true)
    public UUID verifyCredentials(String email, String rawPassword) {
        if (email == null || email.isBlank() || rawPassword == null || rawPassword.isBlank()
                || rawPassword.length() > 72
                || rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidCredentialsException();
        }
        String normalizedEmail = emailNormalizer.normalize(email);
        if (normalizedEmail.length() > 255) {
            throw new InvalidCredentialsException();
        }

        AppUser account = users.findByNormalizedEmail(normalizedEmail).orElse(null);
        boolean eligible = account != null && account.isEnabled()
                && account.getPasswordHash() != null
                && BCRYPT_HASH.matcher(account.getPasswordHash()).matches();
        String hash = eligible ? account.getPasswordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(rawPassword, hash);
        // Even a matching dummy hash must never authenticate an ineligible account.
        if (!eligible || !matches) {
            throw new InvalidCredentialsException();
        }
        return account.getId();
    }
}
