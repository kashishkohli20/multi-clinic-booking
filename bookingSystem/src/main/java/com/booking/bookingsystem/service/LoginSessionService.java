package com.booking.bookingsystem.service;

import com.booking.bookingsystem.config.LoginSessionProperties;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.entity.LoginSession;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.repository.LoginSessionRepository;
import com.booking.bookingsystem.service.exception.SessionCreationNotAllowedException;
import com.booking.bookingsystem.service.exception.InvalidLoginSessionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoginSessionService {
    private final AppUserRepository users;
    private final LoginSessionRepository sessions;
    private final LoginSessionProperties properties;
    private final Clock clock;

    /**
     * Internal operation for a login flow that has already verified credentials.
     * Account eligibility here does not replace checks on subsequent requests.
     * Joins the caller's transaction so later token persistence can roll back together.
     */
    @Transactional
    public LoginSession createSession(UUID accountId) {
        if (accountId == null) {
            throw new SessionCreationNotAllowedException();
        }
        AppUser account = users.findById(accountId)
                .filter(AppUser::isEnabled)
                .orElseThrow(SessionCreationNotAllowedException::new);
        // Hibernate's soft-delete restriction excludes deleted accounts from findById.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LoginSession session = new LoginSession();
        session.setAppUser(account);
        session.setCreatedAt(now);
        session.setLastActivityAt(now);
        session.setExpiresAt(now.plus(properties.maxLifetime()));
        return sessions.saveAndFlush(session);
    }

    /**
     * Checks session ownership against the account ID from verified credentials.
     * This is an internal eligibility check, not JWT verification or authorization.
     * Checking a session does not count as activity and never extends its deadlines.
     */
    @Transactional(readOnly = true)
    public LoginSession requireValidSession(UUID sessionId, UUID accountId) {
        if (sessionId == null || accountId == null) {
            throw new InvalidLoginSessionException();
        }
        LoginSession session = sessions.findForEnabledAccount(sessionId, accountId)
                .orElseThrow(InvalidLoginSessionException::new);
        validateSession(session, clock.instant());
        return session;
    }

    /**
     * For explicit user activity only, never refresh, CSRF bootstrap, or background polling.
     * The caller must supply IDs from verified authentication, not request-body identity.
     */
    @Transactional
    public void recordActivity(UUID sessionId, UUID accountId) {
        if (sessionId == null || accountId == null) {
            throw new InvalidLoginSessionException();
        }
        LoginSession session = sessions.lockForEnabledAccount(sessionId, accountId)
                .orElseThrow(InvalidLoginSessionException::new);
        // Read time after acquiring the lock: time spent waiting cannot revive an expired session.
        Instant now = clock.instant();
        validateSession(session, now);
        session.setLastActivityAt(now.truncatedTo(ChronoUnit.MICROS));
        sessions.flush();
    }

    /**
     * Repeated revocation, missing IDs, and unknown sessions are harmless no-ops.
     * Ownership still scopes the update. Expired sessions and sessions whose account
     * is disabled/deleted can be revoked. Joins the caller's transaction.
     */
    @Transactional
    public void revokeSession(UUID sessionId, UUID accountId) {
        if (sessionId == null || accountId == null) {
            return;
        }
        sessions.revokeIfNotRevoked(sessionId, accountId, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    private void validateSession(LoginSession session, Instant now) {
        if (session.getRevokedAt() != null
                || now.isBefore(session.getLastActivityAt())
                || !now.isBefore(session.getExpiresAt())
                || Duration.between(session.getLastActivityAt(), now).compareTo(properties.idleTimeout()) >= 0) {
            throw new InvalidLoginSessionException();
        }
    }
}
