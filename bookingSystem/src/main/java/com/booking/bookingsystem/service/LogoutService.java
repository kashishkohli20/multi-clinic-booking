package com.booking.bookingsystem.service;

import com.booking.bookingsystem.repository.LoginSessionRepository;
import com.booking.bookingsystem.repository.RefreshTokenRepository;
import com.booking.bookingsystem.security.RefreshTokenCodec;
import com.booking.bookingsystem.service.exception.InvalidAccessTokenException;
import com.booking.bookingsystem.service.exception.InvalidRefreshTokenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LogoutService {
    private final RefreshTokenRepository refreshTokens;
    private final LoginSessionRepository sessions;
    private final RefreshTokenCodec codec;
    private final AccessTokenService accessTokens;
    private final LoginSessionService sessionService;

    /** A known refresh credential selects the session; never revoke both cookie identities. */
    @Transactional
    public void logout(String refreshToken, String accessToken) {
        String hash = null;
        try {
            hash = codec.hash(refreshToken);
        } catch (InvalidRefreshTokenException ignored) {
            // Missing/malformed refresh credentials may still have a valid access fallback.
        }
        if (hash != null) {
            var sessionId = refreshTokens.findSessionIdByTokenHash(hash);
            if (sessionId.isPresent()) {
                var owner = sessions.lockOwnerById(sessionId.get());
                if (owner.isPresent()) {
                    // Includes consumed/expired credentials and disabled/deleted accounts.
                    // The same row lock serializes this revocation with refresh rotation.
                    sessionService.revokeSession(owner.get().getSessionId(), owner.get().getAccountId());
                    return;
                }
            }
        }
        AccessTokenService.Identity identity;
        try {
            identity = accessTokens.verify(accessToken);
        } catch (InvalidAccessTokenException ignored) {
            return; // No trustworthy session identity; clearing browser state is still safe.
        }
        // Conditional update checks ownership and also works for revoked/disabled/deleted sessions.
        sessionService.revokeSession(identity.sessionId(), identity.accountId());
    }
}
