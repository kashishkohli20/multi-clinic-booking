package com.booking.bookingsystem.service;

import com.booking.bookingsystem.service.exception.InvalidCredentialsException;
import com.booking.bookingsystem.service.exception.InvalidLoginSessionException;
import com.booking.bookingsystem.service.exception.InvalidRefreshTokenException;
import com.booking.bookingsystem.service.exception.SessionCreationNotAllowedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoginService {
    private final CredentialVerificationService credentials;
    private final LoginSessionService sessions;
    private final RefreshTokenService refreshTokens;
    private final AccessTokenService accessTokens;

    /** Commit both persisted credentials before the controller sends any cookies. */
    @Transactional
    public Result login(String email, String password) {
        UUID accountId = credentials.verifyCredentials(email, password);
        try {
            var session = sessions.createSession(accountId);
            var refresh = refreshTokens.issueInitial(session.getId(), accountId);
            var access = accessTokens.issue(accountId, session.getId(), session.getExpiresAt());
            return new Result(accountId, access, refresh);
        } catch (SessionCreationNotAllowedException | InvalidRefreshTokenException | InvalidLoginSessionException failure) {
            // Eligibility can change after password verification; do not disclose account state.
            throw new InvalidCredentialsException();
        }
    }

    /** Internal result containing secrets; the controller maps it to a safe DTO. */
    public record Result(UUID accountId, AccessTokenService.IssuedToken access, RefreshTokenService.IssuedToken refresh) {
        @Override public String toString() { return "LoginResult[REDACTED]"; }
    }
}
