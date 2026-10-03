package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.security.RefreshTokenCodec;
import com.booking.bookingsystem.service.AccessTokenService;
import com.booking.bookingsystem.service.LoginService;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "booking.auth.browser.allowed-origins=https://app.example.test")
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
class LogoutHttpIT {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String PASSWORD = "A sample logout passphrase!";
    private static final String PATH = "/api/v1/auth/logout";
    private static final String ORIGIN = "https://app.example.test";
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired LoginService logins;
    @Autowired AccessTokenService accessTokens;
    @Autowired RefreshTokenCodec codec;
    @MockitoBean Clock clock;
    @MockitoSpyBean LoginSessionService sessions;
    private HttpClient client;
    private AppUser user;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        user = new AppUser();
        user.setName("Logout HTTP test");
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
    void logoutRevokesOnlyCurrentSessionClearsAllCookieScopesAndIsRepeatable() throws Exception {
        var login = login();
        var other = login();
        var before = row(login);
        assertEquals(403, protectedGet(login.access().value()).statusCode());
        var response = post(PATH, csrf(), cookies(login));
        assertCleared(response);
        assertEquals(ORIGIN, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        var revoked = row(login);
        assertNotNull(revoked.get("revoked_at"));
        assertEquals(before.get("expires_at"), revoked.get("expires_at"));
        assertEquals(before.get("last_activity_at"), revoked.get("last_activity_at"));
        assertEquals(401, protectedGet(login.access().value()).statusCode());
        assertEquals(401, post("/api/v1/auth/refresh", csrf(), cookies(login)).statusCode());
        assertNull(row(other).get("revoked_at"));
        assertEquals(403, protectedGet(other.access().value()).statusCode());
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertCleared(post(PATH, csrf(), cookies(login)));
        assertEquals(revoked, row(login));
        assertCleared(post(PATH, csrf(), ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired-access", "expired-session", "idle", "disabled", "deleted", "consumed", "malformed-access"})
    void knownRefreshCredentialCanRevokeRegardlessOfEligibility(String reason) throws Exception {
        var login = login();
        String access = login.access().value();
        String successor = null;
        switch (reason) {
            case "expired-access" -> when(clock.instant()).thenReturn(NOW.plusSeconds(900));
            case "expired-session" -> when(clock.instant()).thenReturn(NOW.plusSeconds(8 * 3600));
            case "idle" -> when(clock.instant()).thenReturn(NOW.plusSeconds(1800));
            case "disabled" -> jdbc.update("update app_user set enabled = false where id = ?", user.getId());
            case "deleted" -> users.deleteById(user.getId());
            case "malformed-access" -> access = "broken";
            case "consumed" -> {
                var response = post("/api/v1/auth/refresh", csrf(), cookies(login));
                assertEquals(200, response.statusCode());
                successor = cookie(response, "booking_refresh").getValue();
            }
        }
        assertCleared(post(PATH, csrf(), "booking_refresh=" + login.refresh().value() + "; booking_access=" + access));
        assertNotNull(row(login).get("revoked_at"));
        if (successor != null) assertEquals(401, post("/api/v1/auth/refresh", csrf(), "booking_refresh=" + successor).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "unknown"})
    void validAccessFallbackRevokesSessionWhenRefreshIsUnavailable(String reason) throws Exception {
        var login = login();
        String supplied = "booking_access=" + login.access().value();
        if (reason.equals("malformed")) supplied += "; booking_refresh=broken";
        if (reason.equals("unknown")) supplied += "; booking_refresh=" + codec.generate();
        assertCleared(post(PATH, csrf(), supplied));
        assertNotNull(row(login).get("revoked_at"));
        assertEquals(401, protectedGet(login.access().value()).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "unknown", "expired-access", "wrong-owner"})
    void noUsableIdentityOnlyClearsBrowserState(String reason) throws Exception {
        var login = login();
        String supplied = switch (reason) {
            case "malformed" -> "booking_refresh=broken; booking_access=broken";
            case "unknown" -> "booking_refresh=" + codec.generate();
            case "expired-access" -> "booking_access=" + login.access().value();
            case "wrong-owner" -> "booking_access=" + accessTokens.issue(UUID.randomUUID(), login.refresh().sessionId());
            default -> "";
        };
        if (reason.equals("expired-access")) when(clock.instant()).thenReturn(NOW.plusSeconds(900));
        assertCleared(post(PATH, csrf(), supplied));
        assertNull(row(login).get("revoked_at"));
    }

    @Test
    void knownRefreshSelectsOneSessionEvenIfAccessCookieIdentifiesAnother() throws Exception {
        var first = login();
        var other = login();
        assertCleared(post(PATH, csrf(), "booking_refresh=" + first.refresh().value() + "; booking_access=" + other.access().value()));
        assertNotNull(row(first).get("revoked_at"));
        assertNull(row(other).get("revoked_at"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"booking_access", "booking_refresh"})
    void duplicateCookiesAreRejectedWithoutRevocationOrClearing(String name) throws Exception {
        var login = login();
        String value = name.equals("booking_access") ? login.access().value() : login.refresh().value();
        var response = post(PATH, csrf(), cookies(login) + "; " + name + "=" + value);
        assertEquals(400, response.statusCode());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        assertNull(row(login).get("revoked_at"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-csrf", "wrong-csrf", "wrong-origin"})
    void browserProtectionRejectsBeforeLogout(String reason) throws Exception {
        var login = login();
        var csrf = csrf();
        var builder = request(PATH).header("Cookie", csrf.cookie() + "; " + cookies(login))
                .header("Origin", reason.equals("wrong-origin") ? "https://evil.example.test" : ORIGIN);
        if (!reason.equals("missing-csrf")) builder.header("X-CSRF-TOKEN", reason.equals("wrong-csrf") ? "wrong" : csrf.value());
        var response = client.send(builder.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
        assertNull(row(login).get("revoked_at"));
    }

    @Test
    void bodyHeadersAndQueryCannotSupplyLogoutIdentity() throws Exception {
        var login = login();
        var csrf = csrf();
        var response = client.send(request(PATH + "?refresh_token=" + login.refresh().value())
                .header("Cookie", csrf.cookie()).header("X-CSRF-TOKEN", csrf.value())
                .header("Authorization", "Bearer " + login.access().value()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "sessionId", login.refresh().sessionId(), "accountId", user.getId(), "refreshToken", login.refresh().value())))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertCleared(response);
        assertNull(row(login).get("revoked_at"));
    }

    @Test
    void databaseFailureAfterUpdateRollsBackAndDoesNotClearBrowserCredentials() throws Exception {
        var login = login();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("private database failure");
        }).when(sessions).revokeSession(login.refresh().sessionId(), user.getId());
        var response = post(PATH, csrf(), cookies(login));
        assertEquals(503, response.statusCode());
        assertEquals("Authentication temporarily unavailable.", json.readTree(response.body()).get("message").asText());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        assertFalse(response.body().contains("private database"));
        assertNull(row(login).get("revoked_at"));
        reset(sessions);
        assertCleared(post(PATH, csrf(), cookies(login)));
        assertNotNull(row(login).get("revoked_at"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"logout-first", "refresh-first", "concurrent"})
    void refreshCannotLeaveUsableCredentialsAfterLogout(String order) throws Exception {
        var login = login();
        var csrf = csrf();
        HttpResponse<String> refresh;
        if (order.equals("logout-first")) {
            assertCleared(post(PATH, csrf, cookies(login)));
            refresh = post("/api/v1/auth/refresh", csrf, cookies(login));
            assertEquals(401, refresh.statusCode());
        } else if (order.equals("refresh-first")) {
            refresh = post("/api/v1/auth/refresh", csrf, cookies(login));
            assertEquals(200, refresh.statusCode());
            assertCleared(post(PATH, csrf, cookies(login))); // old consumed token still identifies the session
        } else {
            var barrier = new CyclicBarrier(2);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var logoutTask = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return post(PATH, csrf, cookies(login)); });
                var refreshTask = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return post("/api/v1/auth/refresh", csrf, cookies(login)); });
                assertCleared(logoutTask.get(20, TimeUnit.SECONDS));
                refresh = refreshTask.get(20, TimeUnit.SECONDS);
                assertTrue(refresh.statusCode() == 200 || refresh.statusCode() == 401);
            }
        }
        assertNotNull(row(login).get("revoked_at"));
        assertEquals(401, protectedGet(login.access().value()).statusCode());
        if (refresh.statusCode() == 200) {
            assertEquals(401, protectedGet(cookie(refresh, "booking_access").getValue()).statusCode());
            assertEquals(401, post("/api/v1/auth/refresh", csrf(), "booking_refresh=" + cookie(refresh, "booking_refresh").getValue()).statusCode());
        }
    }

    private LoginService.Result login() { return logins.login(user.getEmail(), PASSWORD); }
    private Map<String, Object> row(LoginService.Result login) {
        return jdbc.queryForMap("select * from login_session where id = ?", login.refresh().sessionId());
    }
    private String cookies(LoginService.Result login) {
        return "booking_access=" + login.access().value() + "; booking_refresh=" + login.refresh().value();
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15));
    }
    private HttpResponse<String> post(String path, Csrf csrf, String cookies) throws Exception {
        return client.send(request(path).header("Cookie", csrf.cookie() + (cookies.isEmpty() ? "" : "; " + cookies))
                .header("X-CSRF-TOKEN", csrf.value()).header("Origin", ORIGIN)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> protectedGet(String access) throws Exception {
        return client.send(request("/api/v1/private").header("Cookie", "booking_access=" + access).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private Csrf csrf() throws Exception {
        var response = client.send(request("/api/v1/auth/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var cookie = cookie(response, "__Host-booking_csrf");
        return new Csrf(cookie.getName() + "=" + cookie.getValue(), json.readTree(response.body()).get("token").asText());
    }
    private HttpCookie cookie(HttpResponse<String> response, String name) {
        return response.headers().allValues("Set-Cookie").stream().flatMap(h -> HttpCookie.parse(h).stream())
                .filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }
    private void assertCleared(HttpResponse<String> response) {
        assertEquals(204, response.statusCode());
        assertEquals("", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(3, response.headers().allValues("Set-Cookie").size());
        Map.of("booking_access", "/api", "booking_refresh", "/api/v1/auth", "__Host-booking_csrf", "/").forEach((name, path) -> {
            var cookie = cookie(response, name);
            assertEquals(0, cookie.getMaxAge());
            assertEquals("", cookie.getValue());
            assertEquals(path, cookie.getPath());
            assertNull(cookie.getDomain());
            assertTrue(cookie.getSecure());
            assertTrue(cookie.isHttpOnly());
            assertTrue(response.headers().allValues("Set-Cookie").stream().anyMatch(h -> h.startsWith(name + "=") && h.contains("SameSite=Lax")));
        });
    }
    private record Csrf(String cookie, String value) { }
}
