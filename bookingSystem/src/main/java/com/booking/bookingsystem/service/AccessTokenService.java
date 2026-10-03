package com.booking.bookingsystem.service;

import com.booking.bookingsystem.config.AccessTokenProperties;
import com.booking.bookingsystem.service.exception.InvalidAccessTokenException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Internal token operations; callers must separately verify current session/account eligibility. */
public class AccessTokenService {
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final AccessTokenProperties properties;
    private final Clock clock;

    public AccessTokenService(JwtEncoder encoder, JwtDecoder decoder, AccessTokenProperties properties, Clock clock) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issue(UUID accountId, UUID sessionId) {
        Objects.requireNonNull(accountId, "Account ID is required");
        Objects.requireNonNull(sessionId, "Session ID is required");
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder().issuer(properties.issuer())
                .audience(List.of(properties.audience())).subject(accountId.toString())
                .claim("sid", sessionId.toString()).claim("token_use", "access")
                .issuedAt(now).notBefore(now).expiresAt(now.plus(properties.lifetime())).build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).type("at+jwt").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Returns validated claims, not an authenticated principal or a resource permission. */
    public Identity verify(String token) {
        if (token == null || token.isBlank() || token.length() > 8192) {
            throw new InvalidAccessTokenException();
        }
        try {
            Jwt jwt = decoder.decode(token);
            return new Identity(UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("sid")), jwt.getExpiresAt());
        } catch (JwtException | IllegalArgumentException exception) {
            // Do not retain parser messages or raw token values in the public failure.
            throw new InvalidAccessTokenException();
        }
    }

    public record Identity(UUID accountId, UUID sessionId, Instant expiresAt) { }
}
