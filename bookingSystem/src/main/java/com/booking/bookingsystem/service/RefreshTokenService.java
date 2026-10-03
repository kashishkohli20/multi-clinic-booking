package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.LoginSession;
import com.booking.bookingsystem.entity.RefreshToken;
import com.booking.bookingsystem.repository.LoginSessionRepository;
import com.booking.bookingsystem.repository.RefreshTokenRepository;
import com.booking.bookingsystem.security.RefreshTokenCodec;
import com.booking.bookingsystem.service.exception.InvalidLoginSessionException;
import com.booking.bookingsystem.service.exception.InvalidRefreshTokenException;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Function;

@Service
public class RefreshTokenService {
    private final RefreshTokenRepository tokens;
    private final LoginSessionRepository sessions;
    private final LoginSessionService sessionService;
    private final RefreshTokenCodec codec;
    private final Clock clock;
    private final EntityManager entityManager;
    private final AccessTokenService accessTokens;
    private final TransactionTemplate rotationTransaction;

    public RefreshTokenService(RefreshTokenRepository tokens, LoginSessionRepository sessions,
                               LoginSessionService sessionService, RefreshTokenCodec codec, Clock clock,
                               EntityManager entityManager, PlatformTransactionManager transactionManager,
                               AccessTokenService accessTokens) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.codec = codec;
        this.clock = clock;
        this.entityManager = entityManager;
        this.accessTokens = accessTokens;
        this.rotationTransaction = new TransactionTemplate(transactionManager);
        this.rotationTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Internal login operation, after credential verification; joins session creation's transaction. */
    @Transactional
    public IssuedToken issueInitial(UUID sessionId, UUID accountId) {
        if (sessionId == null || accountId == null) throw new InvalidRefreshTokenException();
        var owner = sessions.lockOwnerById(sessionId).orElseThrow(InvalidRefreshTokenException::new);
        if (!owner.getAccountId().equals(accountId) || tokens.existsByLoginSession_Id(sessionId)) {
            throw new InvalidRefreshTokenException();
        }
        LoginSession session = requireEligible(owner);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return persist(session, now);
    }

    /**
     * Owns its commit boundary. Reject ambient transactions so a caller cannot undo
     * replay revocation or hold a session lock while waiting for this transaction.
     */
    @Transactional(propagation = Propagation.NEVER)
    public IssuedToken rotate(String rawToken) {
        return rotate(rawToken, Function.identity());
    }

    /** Sign before commit so signing failure also rolls back refresh consumption/replacement. */
    @Transactional(propagation = Propagation.NEVER)
    public Renewal rotateWithAccessToken(String rawToken) {
        return rotate(rawToken, refresh -> {
            try {
                return new Renewal(refresh, accessTokens.issue(refresh.accountId(), refresh.sessionId(), refresh.expiresAt()));
            } catch (InvalidLoginSessionException failure) {
                throw new InvalidRefreshTokenException();
            }
        });
    }

    private <T> T rotate(String rawToken, Function<IssuedToken, T> resultFactory) {
        String hash = codec.hash(rawToken);
        T result = rotationTransaction.execute(status -> {
            UUID sessionId = tokens.findSessionIdByTokenHash(hash).orElseThrow(InvalidRefreshTokenException::new);
            var owner = sessions.lockOwnerById(sessionId).orElseThrow(InvalidRefreshTokenException::new);
            // Load only after the lock, so a waiting duplicate observes committed consumption.
            RefreshToken token = tokens.findByTokenHash(hash).orElseThrow(InvalidRefreshTokenException::new);
            if (token.getConsumedAt() != null) {
                // This also works after account deletion/disablement and after expiry.
                sessionService.revokeSession(sessionId, owner.getAccountId());
                return null; // Commit first; rejecting inside this transaction would roll it back.
            }
            LoginSession session = requireEligible(owner);
            Instant now = clock.instant(); // Recheck token time after any lock wait.
            if (now.isBefore(token.getCreatedAt()) || !now.isBefore(token.getExpiresAt())
                    || token.getCreatedAt().isBefore(session.getCreatedAt())
                    || token.getExpiresAt().isAfter(session.getExpiresAt())) {
                throw new InvalidRefreshTokenException();
            }
            Instant storedNow = now.truncatedTo(ChronoUnit.MICROS);
            token.setConsumedAt(storedNow);
            tokens.flush(); // Release the partial unique index before inserting a successor.
            return resultFactory.apply(persist(session, storedNow));
        });
        if (result == null) throw new InvalidRefreshTokenException();
        return result;
    }

    private LoginSession requireEligible(LoginSessionRepository.SessionOwner owner) {
        var session = sessions.findForEnabledAccount(owner.getSessionId(), owner.getAccountId())
                .orElseThrow(InvalidRefreshTokenException::new);
        // Initial issuance may join a caller that cached this session before taking its lock.
        entityManager.refresh(session);
        try {
            return sessionService.requireValidSession(owner.getSessionId(), owner.getAccountId());
        } catch (InvalidLoginSessionException failure) {
            throw new InvalidRefreshTokenException();
        }
    }

    private IssuedToken persist(LoginSession session, Instant now) {
        String value = codec.generate();
        var token = new RefreshToken();
        token.setLoginSession(session);
        token.setTokenHash(codec.hash(value));
        token.setCreatedAt(now);
        token.setExpiresAt(session.getExpiresAt());
        tokens.saveAndFlush(token);
        return new IssuedToken(session.getAppUser().getId(), session.getId(), value, token.getExpiresAt());
    }

    /** Internal result for the cookie writer, never an HTTP response DTO. */
    public record IssuedToken(UUID accountId, UUID sessionId, String value, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedToken[REDACTED]";
        }
    }

    public record Renewal(IssuedToken refresh, AccessTokenService.IssuedToken access) {
        @Override public String toString() { return "Renewal[REDACTED]"; }
    }
}
