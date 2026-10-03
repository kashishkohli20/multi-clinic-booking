package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.dto.LoginRequest;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.repository.RefreshTokenRepository;
import com.booking.bookingsystem.security.AuthenticationCookies;
import com.booking.bookingsystem.security.RefreshTokenCodec;
import com.booking.bookingsystem.service.AccessTokenService;
import com.booking.bookingsystem.service.LoginService;
import com.booking.bookingsystem.service.LoginSessionService;
import com.booking.bookingsystem.service.exception.SessionCreationNotAllowedException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"booking.auth.session.max-lifetime=5m",
        "booking.auth.browser.allowed-origins=https://app.example.test"})
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LoginHttpIT {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String PASSWORD = "  A sample passphrase!  ";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired RefreshTokenCodec codec;
    @Autowired LoginService logins;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    @MockitoSpyBean AccessTokenService accessTokens;
    @MockitoSpyBean RefreshTokenRepository refreshTokens;
    @MockitoSpyBean LoginSessionService sessions;
    private AppUser user;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        user = new AppUser();
        user.setName("Login test");
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash(encoder.encode(PASSWORD));
        user.setEnabled(true);
        users.saveAndFlush(user);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from refresh_token where session_id in (select id from login_session where user_id = ?)", user.getId());
        jdbc.update("delete from login_session where user_id = ?", user.getId());
        jdbc.update("delete from app_user where id = ?", user.getId());
    }

    @Test
    void loginCommitsSessionAndHashedRefreshTokenAndReturnsOnlySafeMetadata() throws Exception {
        var response = login(" \u2003" + user.getEmail().toUpperCase(Locale.ROOT) + "\t", PASSWORD);
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("https://app.example.test", response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
        var body = json.readTree(response.getContentAsString());
        assertEquals(Set.of("accountId", "accessExpiresAt", "sessionExpiresAt"), body.propertyNames());
        assertEquals(user.getId().toString(), body.get("accountId").asText());
        assertEquals(NOW.plusSeconds(300).toString(), body.get("accessExpiresAt").asText());
        assertEquals(NOW.plusSeconds(300).toString(), body.get("sessionExpiresAt").asText());

        Cookie access = response.getCookie(AuthenticationCookies.ACCESS);
        Cookie refresh = response.getCookie(AuthenticationCookies.REFRESH);
        assertCookie(access, "/api", 300);
        assertCookie(refresh, "/api/v1/auth", 300);
        assertNull(response.getCookie("JSESSIONID"));
        assertEquals(0, response.getCookie("__Host-booking_csrf").getMaxAge());
        var identity = accessTokens.verify(access.getValue());
        assertEquals(user.getId(), identity.accountId());
        var session = jdbc.queryForMap("select * from login_session where id = ?", identity.sessionId());
        assertEquals(user.getId(), session.get("user_id"));
        assertNull(session.get("revoked_at"));
        var stored = jdbc.queryForMap("select * from refresh_token where session_id = ?", identity.sessionId());
        assertEquals(codec.hash(refresh.getValue()), stored.get("token_hash"));
        assertNull(stored.get("consumed_at"));
        assertFalse(response.getContentAsString().contains(access.getValue()));
        assertFalse(response.getContentAsString().contains(refresh.getValue()));
        assertFalse(response.getContentAsString().contains(PASSWORD));
        mvc.perform(get("/api/v1/private").cookie(access)).andExpect(status().isForbidden()); // authenticated, no permission
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong", "trimmed-password", "unknown", "disabled", "deleted", "legacy-hash", "null-email", "null-password", "blank", "oversized"})
    void invalidCredentialsHaveSameGenericFailureAndNoCookiesOrSession(String scenario) throws Exception {
        String email = user.getEmail();
        String password = PASSWORD;
        switch (scenario) {
            case "wrong" -> password = "Wrong password!";
            case "trimmed-password" -> password = PASSWORD.strip();
            case "unknown" -> email = "missing-" + email;
            case "disabled" -> jdbc.update("update app_user set enabled = false where id = ?", user.getId());
            case "deleted" -> users.deleteById(user.getId());
            case "legacy-hash" -> jdbc.update("update app_user set password_hash = null where id = ?", user.getId());
            case "null-email" -> email = null;
            case "null-password" -> password = null;
            case "blank" -> password = " ";
            case "oversized" -> password = "x".repeat(73);
        }
        var response = login(email, password);
        assertEquals(401, response.getStatus());
        assertEquals("Invalid email or password.", json.readTree(response.getContentAsString()).get("message").asText());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
        assertNoSession();
    }

    @Test
    void loginRequiresCsrfAndAllowedOriginBeforeVerifyingPassword() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(payload(user.getEmail(), PASSWORD)))
                .andExpect(status().isForbidden());
        var csrf = csrf();
        mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie()).header("X-CSRF-TOKEN", csrf.value())
                        .header("Origin", "https://evil.example.test").contentType("application/json")
                        .content(payload(user.getEmail(), PASSWORD))).andExpect(status().isForbidden());
        assertNoSession();
    }

    @Test
    void malformedJsonReturns400WithoutCredentialsOrState() throws Exception {
        var csrf = csrf();
        var response = mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie()).header("X-CSRF-TOKEN", csrf.value())
                        .contentType("application/json").content("{\"password\":\"secret\""))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertFalse(response.getContentAsString().contains("secret"));
        assertNull(response.getCookie(AuthenticationCookies.ACCESS));
        assertNoSession();
    }

    @Test
    void clientIdentityRolesAndSessionFieldsCannotChooseLoginIdentity() throws Exception {
        var csrf = csrf();
        var response = mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie()).header("X-CSRF-TOKEN", csrf.value())
                        .contentType("application/json").content(json.writeValueAsString(Map.of(
                                "email", user.getEmail(), "password", PASSWORD, "accountId", UUID.randomUUID(),
                                "sessionId", UUID.randomUUID(), "roles", Set.of("CLINIC_ADMIN")))))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(user.getId(), accessTokens.verify(response.getCookie(AuthenticationCookies.ACCESS).getValue()).accountId());
    }

    @Test
    void separateLoginsCreateIndependentSessionsAndOldAccessCookieDoesNotBlockLogin() throws Exception {
        var first = login(user.getEmail(), PASSWORD);
        var second = login(user.getEmail(), PASSWORD); // helper always supplies an invalid old access cookie
        var one = accessTokens.verify(first.getCookie(AuthenticationCookies.ACCESS).getValue());
        var two = accessTokens.verify(second.getCookie(AuthenticationCookies.ACCESS).getValue());
        assertNotEquals(one.sessionId(), two.sessionId());
        assertNotNull(sessions.requireValidSession(one.sessionId(), user.getId()));
        assertNotNull(sessions.requireValidSession(two.sessionId(), user.getId()));
        assertEquals(2, jdbc.queryForObject("select count(*) from login_session where user_id = ?", Integer.class, user.getId()));
    }

    @Test
    void successfulLoginClearsCsrfSoBrowserBootstrapsNewState() throws Exception {
        var old = csrf();
        var response = mvc.perform(post("/api/v1/auth/login").cookie(old.cookie()).header("X-CSRF-TOKEN", old.value())
                        .contentType("application/json").content(payload(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(0, response.getCookie("__Host-booking_csrf").getMaxAge());
        var fresh = csrf(); // browser removed old cookie following Max-Age=0
        assertNotEquals(old.cookie().getValue(), fresh.cookie().getValue());
        mvc.perform(post("/api/v1/auth/login").cookie(fresh.cookie()).header("X-CSRF-TOKEN", old.value())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").cookie(fresh.cookie()).header("X-CSRF-TOKEN", fresh.value())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()); // fresh CSRF reaches credential verification
    }

    @Test
    void signingFailureRollsBackSessionAndRefreshCredential() {
        doThrow(new JwtEncodingException("simulated signer failure")).when(accessTokens).issue(any(), any(), any());
        assertThrows(JwtEncodingException.class, () -> logins.login(user.getEmail(), PASSWORD));
        assertNoSession();
    }

    @Test
    void refreshPersistenceFailureRollsBackSession() {
        doThrow(new DataAccessResourceFailureException("simulated database failure")).when(refreshTokens).saveAndFlush(any());
        assertThrows(DataAccessResourceFailureException.class, () -> logins.login(user.getEmail(), PASSWORD));
        assertNoSession();
    }

    @Test
    void eligibilityChangeAfterPasswordCheckStillReturnsGenericFailure() throws Exception {
        doThrow(new SessionCreationNotAllowedException()).when(sessions).createSession(user.getId());
        var response = login(user.getEmail(), PASSWORD);
        assertEquals(401, response.getStatus());
        assertEquals("Invalid email or password.", json.readTree(response.getContentAsString()).get("message").asText());
        assertNull(response.getCookie(AuthenticationCookies.ACCESS));
        assertNoSession();
    }

    @Test
    void requestAndInternalResultDoNotLogSecrets() {
        var request = new LoginRequest(user.getEmail(), PASSWORD);
        assertEquals("LoginRequest[REDACTED]", request.toString());
        assertEquals("LoginResult[REDACTED]", logins.login(user.getEmail(), PASSWORD).toString());
    }

    private MockHttpServletResponse login(String email, String password) throws Exception {
        var csrf = csrf();
        return mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie(), new Cookie(AuthenticationCookies.ACCESS, "expired"))
                        .header("X-CSRF-TOKEN", csrf.value()).header("Origin", "https://app.example.test")
                        .contentType("application/json").content(payload(email, password)))
                .andReturn().getResponse();
    }

    private String payload(String email, String password) { return json.writeValueAsString(new LoginRequest(email, password)); }

    private Csrf csrf() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        return new Csrf(response.getCookie("__Host-booking_csrf"), json.readTree(response.getContentAsString()).get("token").asText());
    }

    private void assertNoSession() {
        assertEquals(0, jdbc.queryForObject("select count(*) from login_session where user_id = ?", Integer.class, user.getId()));
        // The FK prevents a refresh credential from surviving the rolled-back session.
    }

    private void assertCookie(Cookie cookie, String path, int age) {
        assertNotNull(cookie);
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
        assertNull(cookie.getDomain());
        assertEquals(path, cookie.getPath());
        assertEquals(age, cookie.getMaxAge());
    }

    private record Csrf(Cookie cookie, String value) { }
}
