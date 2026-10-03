package com.booking.bookingsystem.service;

import com.booking.bookingsystem.config.AccessTokenConfiguration;
import com.booking.bookingsystem.config.AccessTokenProperties;
import com.booking.bookingsystem.security.JwtTestKeys;
import com.booking.bookingsystem.service.exception.InvalidAccessTokenException;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessTokenServiceTest {
    private static final KeyPair KEYS = JwtTestKeys.generate(2048);
    private static final KeyPair OTHER_KEYS = JwtTestKeys.generate(2048);
    private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private final Clock clock = mock(Clock.class);
    private AccessTokenService service;

    @BeforeEach
    void setUp() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        var properties = new AccessTokenProperties("https://issuer.example.test", "booking-api",
                resource("PRIVATE KEY", KEYS.getPrivate().getEncoded()),
                resource("PUBLIC KEY", KEYS.getPublic().getEncoded()), Duration.ofMinutes(15));
        service = new AccessTokenConfiguration().accessTokenService(properties, clock);
    }

    @Test
    void issuesMinimalRs256AccessTokenAndReturnsOnlyValidatedIdentity() throws Exception {
        when(clock.instant()).thenReturn(NOW.plusNanos(999_999_999));
        String token = service.issue(ACCOUNT, SESSION);
        var jwt = SignedJWT.parse(token);
        assertEquals(JWSAlgorithm.RS256, jwt.getHeader().getAlgorithm());
        assertEquals("at+jwt", jwt.getHeader().getType().toString());
        assertEquals(Set.of("iss", "aud", "sub", "sid", "token_use", "iat", "nbf", "exp"),
                jwt.getJWTClaimsSet().getClaims().keySet());
        assertEquals(Date.from(NOW), jwt.getJWTClaimsSet().getIssueTime());
        assertEquals(Date.from(NOW), jwt.getJWTClaimsSet().getNotBeforeTime());
        assertEquals(new AccessTokenService.Identity(ACCOUNT, SESSION, NOW.plusSeconds(900)), service.verify(token));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void expiryHasNoGracePeriod(long nanos) {
        String token = service.issue(ACCOUNT, SESSION);
        when(clock.instant()).thenReturn(NOW.plusSeconds(900).plusNanos(nanos));
        if (nanos < 0) assertEquals(ACCOUNT, service.verify(token).accountId());
        else assertInvalid(token);
    }

    @ParameterizedTest
    @ValueSource(strings = {"iss", "aud", "sub", "sid", "token_use", "iat", "nbf", "exp"})
    void rejectsMissingRequiredClaim(String name) throws Exception {
        var claims = claims();
        claims.remove(name);
        assertInvalid(sign(claims, JWSAlgorithm.RS256, "at+jwt", KEYS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "extra-audience", "subject", "session", "short-uuid",
            "session-type", "purpose", "type", "missing-type", "future-issued", "future-not-before",
            "past-not-before", "expired", "too-long", "inverted", "invalid-expiry"})
    void rejectsIncorrectClaimsEvenWithTrustedSignature(String scenario) throws Exception {
        var claims = claims();
        String type = "at+jwt";
        switch (scenario) {
            case "issuer" -> claims.put("iss", "https://other.example.test");
            case "audience" -> claims.put("aud", List.of("other-api"));
            case "extra-audience" -> claims.put("aud", List.of("booking-api", "other-api"));
            case "subject" -> claims.put("sub", "not-a-uuid");
            case "session" -> claims.put("sid", "not-a-uuid");
            case "short-uuid" -> claims.put("sid", "1-1-1-1-1");
            case "session-type" -> claims.put("sid", List.of(SESSION.toString()));
            case "purpose" -> claims.put("token_use", "refresh");
            case "type" -> type = "JWT";
            case "missing-type" -> type = null;
            case "future-issued" -> claims.put("iat", Date.from(NOW.plusSeconds(1)));
            case "future-not-before" -> claims.put("nbf", Date.from(NOW.plusSeconds(1)));
            case "past-not-before" -> claims.put("nbf", Date.from(NOW.minusSeconds(1)));
            case "expired" -> claims.put("exp", Date.from(NOW));
            case "too-long" -> claims.put("exp", Date.from(NOW.plusSeconds(901)));
            case "inverted" -> claims.put("exp", Date.from(NOW.minusSeconds(1)));
            case "invalid-expiry" -> claims.put("exp", "not-a-timestamp");
            default -> throw new IllegalArgumentException(scenario);
        }
        assertInvalid(sign(claims, JWSAlgorithm.RS256, type, KEYS));
    }

    @Test
    void rejectsAnotherSigningKey() throws Exception {
        assertInvalid(sign(claims(), JWSAlgorithm.RS256, "at+jwt", OTHER_KEYS));
    }

    @Test
    void rejectsAnotherAlgorithmEvenWithSameKey() throws Exception {
        assertInvalid(sign(claims(), JWSAlgorithm.RS512, "at+jwt", KEYS));
    }

    @Test
    void rejectsUnsignedAndSymmetricTokens() throws Exception {
        assertInvalid(new PlainJWT(claimSet(claims())).serialize());
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("at+jwt")).build(),
                claimSet(claims()));
        jwt.sign(new MACSigner(new byte[32]));
        assertInvalid(jwt.serialize());
    }

    @Test
    void rejectsPayloadTampering() {
        String[] parts = service.issue(ACCOUNT, SESSION).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace(ACCOUNT.toString(), UUID.randomUUID().toString());
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertInvalid(String.join(".", parts));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-jwt", "a.b.c"})
    void rejectsMissingOrMalformedToken(String token) {
        assertInvalid(token);
    }

    @Test
    void rejectsOversizedTokenAndMissingIssuingIdentity() {
        assertInvalid("x".repeat(8193));
        assertThrows(NullPointerException.class, () -> service.issue(null, SESSION));
        assertThrows(NullPointerException.class, () -> service.issue(ACCOUNT, null));
    }

    private void assertInvalid(String token) {
        var failure = assertThrows(InvalidAccessTokenException.class, () -> service.verify(token));
        assertEquals("Invalid access token.", failure.getMessage());
        assertNull(failure.getCause());
    }

    private Map<String, Object> claims() {
        var claims = new HashMap<String, Object>();
        claims.put("iss", "https://issuer.example.test");
        claims.put("aud", List.of("booking-api"));
        claims.put("sub", ACCOUNT.toString());
        claims.put("sid", SESSION.toString());
        claims.put("token_use", "access");
        claims.put("iat", Date.from(NOW));
        claims.put("nbf", Date.from(NOW));
        claims.put("exp", Date.from(NOW.plusSeconds(900)));
        return claims;
    }

    private JWTClaimsSet claimSet(Map<String, Object> claims) {
        var builder = new JWTClaimsSet.Builder();
        claims.forEach(builder::claim);
        return builder.build();
    }

    private String sign(Map<String, Object> claims, JWSAlgorithm algorithm, String type, KeyPair keys) throws Exception {
        var header = new JWSHeader.Builder(algorithm);
        if (type != null) header.type(new JOSEObjectType(type));
        var jwt = new SignedJWT(header.build(), claimSet(claims));
        jwt.sign(new RSASSASigner(keys.getPrivate()));
        return jwt.serialize();
    }

    private ByteArrayResource resource(String type, byte[] encoded) {
        return new ByteArrayResource(JwtTestKeys.pem(type, encoded).getBytes(StandardCharsets.US_ASCII));
    }
}
