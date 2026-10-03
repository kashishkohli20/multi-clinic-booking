package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.repository.RefreshTokenRepository;
import com.booking.bookingsystem.security.RefreshTokenCodec;
import com.booking.bookingsystem.service.AccessTokenService;
import com.booking.bookingsystem.service.LoginSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "booking.auth.session.max-lifetime=20m", "booking.auth.session.idle-timeout=18m",
        "booking.auth.browser.allowed-origins=https://app.example.test"})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
class RefreshHttpIT {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String PASSWORD = "A sample passphrase for refresh!";
    private static final String PATH = "/api/v1/auth/refresh";
    private static final String ORIGIN = "https://app.example.test";
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired RefreshTokenCodec codec;
    @Autowired LoginSessionService sessions;
    @MockitoBean Clock clock;
    @MockitoSpyBean AccessTokenService accessTokens;
    @MockitoSpyBean RefreshTokenRepository tokens;
    private HttpClient client;
    private AppUser user;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        user = new AppUser();
        user.setName("Refresh HTTP test");
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(encoder.encode(PASSWORD));
        user.setEnabled(true);
        users.saveAndFlush(user);
    }

    @AfterEach
    void cleanup() {
        client.close();
        jdbc.update("delete from refresh_token where session_id in (select id from login_session where user_id = ?)", user.getId());
        jdbc.update("delete from login_session where user_id = ?", user.getId());
        jdbc.update("delete from app_user where id = ?", user.getId());
    }

    @Test
    void expiredAccessCanRefreshWithSameSessionAndFixedDeadlines() throws Exception {
        var login = login();
        var before = session(login.sessionId());
        when(clock.instant()).thenReturn(NOW.plusSeconds(900));
        assertEquals(401, protectedGet(login.access()).statusCode());
        var response = refresh(login);
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(ORIGIN, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        var body = json.readTree(response.body());
        assertEquals(Set.of("accountId", "accessExpiresAt", "sessionExpiresAt"), body.propertyNames());
        assertEquals(user.getId().toString(), body.get("accountId").asText());
        assertEquals(NOW.plusSeconds(1200).toString(), body.get("accessExpiresAt").asText());
        assertEquals(NOW.plusSeconds(1200).toString(), body.get("sessionExpiresAt").asText());
        var access = cookie(response, "booking_access");
        var refresh = cookie(response, "booking_refresh");
        assertCookie(response, access, "/api", 300);
        assertCookie(response, refresh, "/api/v1/auth", 300);
        assertEquals(2, response.headers().allValues("Set-Cookie").size()); // CSRF is retained, no servlet session.
        assertNotEquals(login.refresh(), refresh.getValue());
        assertNotEquals(login.access(), access.getValue());
        assertFalse(response.body().contains(access.getValue()));
        assertFalse(response.body().contains(refresh.getValue()));
        assertEquals(login.sessionId(), accessTokens.verify(access.getValue()).sessionId());
        assertEquals(before, session(login.sessionId())); // no activity, deadline or version changes
        assertEquals(2, tokenCount(login.sessionId()));
        assertNotNull(jdbc.queryForObject("select consumed_at from refresh_token where token_hash = ?", java.sql.Timestamp.class, codec.hash(login.refresh())));
        assertEquals(1, jdbc.queryForObject("select count(*) from refresh_token where token_hash = ? and consumed_at is null",
                Integer.class, codec.hash(refresh.getValue())));
        assertEquals(403, protectedGet(access.getValue()).statusCode()); // authenticated; booking permissions still denied
        when(clock.instant()).thenReturn(NOW.plusSeconds(1080));
        assertRejected(send(PATH, login.csrf(), "booking_refresh=" + refresh.getValue(), "", ORIGIN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "blank", "malformed", "unknown", "duplicate", "expired", "idle", "revoked", "disabled", "deleted", "future"})
    void rejectionIsGenericClearsCookiesAndDoesNotRotate(String reason) throws Exception {
        var login = login();
        String supplied = "booking_refresh=" + login.refresh();
        switch (reason) {
            case "missing" -> supplied = "";
            case "blank" -> supplied = "booking_refresh=";
            case "malformed" -> supplied = "booking_refresh=broken";
            case "unknown" -> supplied = "booking_refresh=" + codec.generate();
            case "duplicate" -> supplied += "; " + supplied;
            case "expired" -> when(clock.instant()).thenReturn(NOW.plusSeconds(1200));
            case "idle" -> when(clock.instant()).thenReturn(NOW.plusSeconds(1080));
            case "revoked" -> sessions.revokeSession(login.sessionId(), user.getId());
            case "disabled" -> jdbc.update("update app_user set enabled = false where id = ?", user.getId());
            case "deleted" -> users.deleteById(user.getId());
            case "future" -> when(clock.instant()).thenReturn(NOW.minusSeconds(1));
        }
        var before = session(login.sessionId());
        assertRejected(send(PATH, login.csrf(), supplied, "", ORIGIN));
        assertEquals(before, session(login.sessionId()));
        assertEquals(1, tokenCount(login.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from refresh_token where session_id = ? and consumed_at is null",
                Integer.class, login.sessionId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-csrf", "wrong-csrf", "wrong-origin"})
    void browserProtectionRejectsBeforeRotation(String reason) throws Exception {
        var login = login();
        var csrf = switch (reason) {
            case "missing-csrf" -> new Csrf(login.csrf().cookie(), "");
            case "wrong-csrf" -> new Csrf(login.csrf().cookie(), "wrong");
            default -> login.csrf();
        };
        var response = send(PATH, csrf, "booking_refresh=" + login.refresh(), "",
                reason.equals("wrong-origin") ? "https://evil.example.test" : ORIGIN);
        assertEquals(403, response.statusCode());
        assertEquals(1, tokenCount(login.sessionId()));
        assertEquals(200, refresh(login).statusCode()); // rejected request did not consume it
    }

    @Test
    void refreshValueInJsonHeaderOrQueryCannotReplaceCookie() throws Exception {
        var login = login();
        var request = HttpRequest.newBuilder(uri(PATH + "?refresh_token=" + login.refresh()))
                .timeout(Duration.ofSeconds(15)).header("Cookie", login.csrf().cookie())
                .header("X-CSRF-TOKEN", login.csrf().value()).header("Authorization", "Bearer " + login.refresh())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(Map.of("refreshToken", login.refresh())))).build();
        assertRejected(client.send(request, HttpResponse.BodyHandlers.ofString()));
        assertEquals(1, tokenCount(login.sessionId()));
    }

    @Test
    void bodyIdentityIsIgnoredAndNoAccessCookieIsRequired() throws Exception {
        var login = login();
        var response = send(PATH, login.csrf(), "booking_refresh=" + login.refresh(),
                json.writeValueAsString(Map.of("accountId", UUID.randomUUID(), "sessionId", UUID.randomUUID())), ORIGIN);
        assertEquals(200, response.statusCode());
        assertEquals(user.getId().toString(), json.readTree(response.body()).get("accountId").asText());
        assertEquals(login.sessionId(), accessTokens.verify(cookie(response, "booking_access").getValue()).sessionId());
    }

    @Test
    void replayCommitsRevocationAndRejectsSuccessorWithoutAffectingOtherSession() throws Exception {
        var login = login();
        var other = login();
        var response = refresh(login);
        assertEquals(200, response.statusCode());
        String successor = cookie(response, "booking_refresh").getValue();
        assertRejected(refresh(login));
        assertNotNull(session(login.sessionId()).get("revoked_at"));
        assertEquals(401, protectedGet(cookie(response, "booking_access").getValue()).statusCode());
        assertRejected(send(PATH, login.csrf(), "booking_refresh=" + successor, "", ORIGIN));
        assertEquals(200, refresh(other).statusCode());
    }

    @Test
    void concurrentHttpRefreshesProduceOneSuccessOneReplayAndRevokeSession() throws Exception {
        var login = login();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return refresh(login); });
            var second = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return refresh(login); });
            var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(List.of(200, 401), responses.stream().map(HttpResponse::statusCode).sorted().toList());
            assertEquals(2, tokenCount(login.sessionId()));
            assertNotNull(session(login.sessionId()).get("revoked_at"));
            var winner = responses.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow();
            assertEquals(401, protectedGet(cookie(winner, "booking_access").getValue()).statusCode());
            assertRejected(send(PATH, login.csrf(), "booking_refresh=" + cookie(winner, "booking_refresh").getValue(), "", ORIGIN));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"signing", "storage"})
    void infrastructureFailureReturns503AndRollsBackWithoutOverwritingCookies(String reason) throws Exception {
        var login = login();
        if (reason.equals("signing")) {
            doThrow(new JwtEncodingException("private signing failure")).when(accessTokens).issue(any(), any(), any());
        } else {
            doThrow(new DataAccessResourceFailureException("private database failure")).when(tokens).saveAndFlush(any());
        }
        var response = refresh(login);
        assertEquals(503, response.statusCode());
        assertEquals("Authentication temporarily unavailable.", json.readTree(response.body()).get("message").asText());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        assertFalse(response.body().contains("private"));
        assertEquals(1, tokenCount(login.sessionId()));
        assertNull(session(login.sessionId()).get("revoked_at"));
        reset(accessTokens, tokens);
        assertEquals(200, refresh(login).statusCode()); // known rollback left the original credential usable
    }

    private Login login() throws Exception {
        var response = send("/api/v1/auth/login", csrf(), "",
                json.writeValueAsString(Map.of("email", user.getEmail(), "password", PASSWORD)), ORIGIN);
        assertEquals(200, response.statusCode());
        String access = cookie(response, "booking_access").getValue();
        return new Login(access, cookie(response, "booking_refresh").getValue(),
                accessTokens.verify(access).sessionId(), csrf());
    }

    private HttpResponse<String> refresh(Login login) throws Exception {
        return send(PATH, login.csrf(), "booking_refresh=" + login.refresh() + "; booking_access=" + login.access(), "", ORIGIN);
    }

    private HttpResponse<String> send(String path, Csrf csrf, String cookies, String body, String origin) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
                .header("Cookie", csrf.cookie() + (cookies.isEmpty() ? "" : "; " + cookies))
                .header("X-CSRF-TOKEN", csrf.value()).header("Origin", origin).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Csrf csrf() throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var cookie = cookie(response, "__Host-booking_csrf");
        return new Csrf(cookie.getName() + "=" + cookie.getValue(), json.readTree(response.body()).get("token").asText());
    }

    private HttpResponse<String> protectedGet(String access) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/v1/private")).timeout(Duration.ofSeconds(15))
                .header("Cookie", "booking_access=" + access).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpCookie cookie(HttpResponse<String> response, String name) {
        return response.headers().allValues("Set-Cookie").stream().flatMap(h -> HttpCookie.parse(h).stream())
                .filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }

    private void assertRejected(HttpResponse<String> response) {
        assertEquals(401, response.statusCode());
        assertEquals("Invalid refresh token.", json.readTree(response.body()).get("message").asText());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertCookie(response, cookie(response, "booking_access"), "/api", 0);
        assertCookie(response, cookie(response, "booking_refresh"), "/api/v1/auth", 0);
    }

    private void assertCookie(HttpResponse<String> response, HttpCookie cookie, String path, long age) {
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertNull(cookie.getDomain());
        assertEquals(path, cookie.getPath());
        assertEquals(age, cookie.getMaxAge());
        assertTrue(response.headers().allValues("Set-Cookie").stream()
                .anyMatch(h -> h.startsWith(cookie.getName() + "=") && h.contains("SameSite=Lax")));
    }

    private Map<String, Object> session(UUID id) { return jdbc.queryForMap("select * from login_session where id = ?", id); }
    private int tokenCount(UUID id) { return jdbc.queryForObject("select count(*) from refresh_token where session_id = ?", Integer.class, id); }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private record Csrf(String cookie, String value) { }
    private record Login(String access, String refresh, UUID sessionId, Csrf csrf) { }
}
