package com.booking.bookingsystem.security;

import com.booking.bookingsystem.config.AccessTokenProperties;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public final class AccessTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2TokenValidatorResult INVALID = OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "Invalid access token.", null));
    private final AccessTokenProperties properties;
    private final Clock clock;

    public AccessTokenClaimsValidator(AccessTokenProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            var issued = token.getIssuedAt();
            var expires = token.getExpiresAt();
            var notBefore = token.getNotBefore();
            var now = clock.instant();
            if (!"RS256".equals(token.getHeaders().get("alg"))
                    || !"at+jwt".equals(token.getHeaders().get("typ"))
                    || !"access".equals(token.getClaims().get("token_use"))
                    || !properties.issuer().equals(token.getClaims().get("iss"))
                    || !List.of(properties.audience()).equals(token.getAudience())
                    || !isUuid(token.getClaims().get("sub")) || !isUuid(token.getClaims().get("sid"))
                    || issued == null || expires == null || notBefore == null
                    || issued.isAfter(now) || notBefore.isAfter(now) || !notBefore.equals(issued)
                    || !expires.isAfter(now) || !expires.isAfter(issued)
                    || Duration.between(issued, expires).compareTo(properties.lifetime()) > 0) {
                return INVALID;
            }
            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException exception) {
            return INVALID;
        }
    }

    private boolean isUuid(Object value) {
        return value instanceof String text && UUID.fromString(text).toString().equals(text);
    }
}
