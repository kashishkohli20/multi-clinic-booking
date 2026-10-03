package com.booking.bookingsystem.security;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.service.AccessTokenService;
import com.booking.bookingsystem.service.BookingService;
import com.booking.bookingsystem.service.LoginSessionService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "booking.auth.browser.allowed-origins=https://app.example.test")
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BrowserSecurityIT {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String ORIGIN = "https://app.example.test";
    private final List<UUID> accounts = new ArrayList<>();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired AccessTokenService jwt;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    @MockitoSpyBean LoginSessionService sessions;
    @MockitoSpyBean BookingService booking;

    @BeforeEach
    void time() { when(clock.instant()).thenReturn(NOW); }

    @AfterEach
    void cleanup() {
        for (UUID id : accounts) {
            jdbc.update("delete from login_session where user_id = ?", id);
            jdbc.update("delete from app_user where id = ?", id);
        }
    }

    @Test
    void csrfBootstrapReturnsMaskedHeaderTokenAndSecureHostCookieWithoutSession() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf").header("Origin", ORIGIN))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN")).andReturn();
        Cookie cookie = result.getResponse().getCookie("__Host-booking_csrf");
        assertNotNull(cookie);
        assertTrue(cookie.getSecure());
        assertTrue(cookie.isHttpOnly());
        assertEquals("/", cookie.getPath());
        assertNull(cookie.getDomain());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
        assertNull(result.getRequest().getSession(false));
        assertNull(result.getResponse().getCookie("JSESSIONID"));
        assertNotEquals(cookie.getValue(), json.readTree(result.getResponse().getContentAsString()).get("token").asText());
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "header-only", "cookie-only", "wrong-header", "query-parameter"})
    void registrationRejectsMissingOrInvalidCsrfBeforeCallingController(String scenario) throws Exception {
        Csrf csrf = csrf();
        var request = post("/api/v1/auth/register").contentType("application/json").content("{}");
        if (!scenario.equals("header-only") && !scenario.equals("missing")) request.cookie(csrf.cookie());
        if (scenario.equals("header-only")) request.header("X-CSRF-TOKEN", csrf.value());
        if (scenario.equals("wrong-header")) request.header("X-CSRF-TOKEN", "wrong");
        if (scenario.equals("query-parameter")) request.param("_csrf", csrf.value());
        mvc.perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Access denied."));
    }

    @Test
    void validCsrfReachesRegistrationWithoutAnAccessToken() throws Exception {
        var csrf = csrf();
        mvc.perform(post("/api/v1/auth/register").cookie(csrf.cookie()).header("X-CSRF-TOKEN", csrf.value())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.email").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/slots/hold", "/api/v1/confirm"})
    void bookingWritesAreDeniedForAnonymousAndAuthenticatedCallers(String path) throws Exception {
        var csrf = csrf();
        mvc.perform(post(path).cookie(csrf.cookie()).header("X-CSRF-TOKEN", csrf.value())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        var identity = identity();
        mvc.perform(post(path).cookie(csrf.cookie(), access(identity.token())).header("X-CSRF-TOKEN", csrf.value())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(booking);
    }

    @Test
    void validCookieAuthenticatesWithoutPersistingContextOrRecordingActivity() throws Exception {
        var identity = identity();
        var before = jdbc.queryForMap("select * from login_session where id = ?", identity.session());
        var result = mvc.perform(get("/api/v1/private").cookie(access(identity.token())))
                .andExpect(status().isForbidden()).andReturn(); // Authenticated, but no permission granted.
        assertNull(result.getRequest().getSession(false));
        assertEquals(before, jdbc.queryForMap("select * from login_session where id = ?", identity.session()));
        mvc.perform(get("/api/v1/private")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "expired-jwt", "revoked", "disabled", "deleted", "wrong-owner", "missing-session", "idle", "absolute"})
    void invalidCredentialsNeverAuthenticate(String reason) throws Exception {
        var identity = identity();
        String token = identity.token();
        switch (reason) {
            case "malformed" -> token = "broken";
            case "expired-jwt" -> when(clock.instant()).thenReturn(NOW.plusSeconds(900));
            case "revoked" -> sessions.revokeSession(identity.session(), identity.account());
            case "disabled" -> jdbc.update("update app_user set enabled = false where id = ?", identity.account());
            case "deleted" -> users.deleteById(identity.account());
            case "wrong-owner" -> token = jwt.issue(UUID.randomUUID(), identity.session());
            case "missing-session" -> token = jwt.issue(identity.account(), UUID.randomUUID());
            case "idle" -> {
                when(clock.instant()).thenReturn(NOW.plusSeconds(1800));
                token = jwt.issue(identity.account(), identity.session());
            }
            case "absolute" -> {
                jdbc.update("update login_session set last_activity_at = ? where id = ?",
                        Timestamp.from(NOW.plusSeconds(8 * 3600 - 1)), identity.session());
                when(clock.instant()).thenReturn(NOW.plusSeconds(8 * 3600));
                token = jwt.issue(identity.account(), identity.session());
            }
        }
        mvc.perform(get("/api/v1/private").cookie(access(token)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Authentication required."));
    }

    @Test
    void disablingAccountIsObservedOnNextRequest() throws Exception {
        var identity = identity();
        mvc.perform(get("/api/v1/private").cookie(access(identity.token()))).andExpect(status().isForbidden());
        jdbc.update("update app_user set enabled = false where id = ?", identity.account());
        mvc.perform(get("/api/v1/private").cookie(access(identity.token()))).andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateCookiesAreRejectedAndHeaderOrQueryCredentialsAreNotAccepted() throws Exception {
        var identity = identity();
        mvc.perform(get("/api/v1/private").cookie(access(identity.token()), access(identity.token())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/private").header("Authorization", "Bearer " + identity.token()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/private").param("access_token", identity.token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicRoutesAndFutureRenewalRoutesDoNotRequireAValidAccessCookie() throws Exception {
        mvc.perform(get("/actuator/health").cookie(access("expired"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/slots/search").param("doctorName", "unknown").cookie(access("expired")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Doctor not found"));
        verify(booking).findAvailableSlotsForDoctor("unknown");
        mvc.perform(get("/api/v1/auth/csrf").cookie(access("expired"))).andExpect(status().isOk());
        var csrf = csrf();
        for (String path : List.of("logout")) {
            mvc.perform(post("/api/v1/auth/" + path).cookie(csrf.cookie(), access("expired"))
                            .header("X-CSRF-TOKEN", csrf.value()))
                    .andExpect(status().isNotFound()); // Security permits entry; endpoints are separate chunks.
        }
    }

    @Test
    void databaseFailureFailsClosedWithGeneric503() throws Exception {
        var identity = identity();
        doThrow(new DataAccessResourceFailureException("private database details"))
                .when(sessions).requireValidSession(identity.session(), identity.account());
        mvc.perform(get("/api/v1/private").cookie(access(identity.token())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":503,\"message\":\"Authentication temporarily unavailable.\"}"));
        verifyNoInteractions(booking);
    }

    @Test
    void allowsExplicitCredentialedPreflightWithoutAuthentication() throws Exception {
        mvc.perform(options("/api/v1/slots/hold").header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type,X-CSRF-TOKEN"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        verifyNoInteractions(sessions, booking);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example.test", "https://app.example.test.evil.test", "null"})
    void deniesUnlistedCrossOriginRequestsAndPreflights(String origin) throws Exception {
        mvc.perform(options("/api/v1/auth/register").header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/v1/auth/csrf").header("Origin", origin)).andExpect(status().isForbidden());
    }

    @Test
    void unrelatedRoutesNeverFallBackToFormLoginOrDefaultLogout() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Location"));
        var csrf = csrf();
        mvc.perform(post("/logout").cookie(csrf.cookie()).header("X-CSRF-TOKEN", csrf.value()))
                .andExpect(status().isUnauthorized());
    }

    private Identity identity() {
        var user = new AppUser();
        user.setName("Browser security test");
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setEnabled(true);
        users.saveAndFlush(user);
        accounts.add(user.getId());
        var session = sessions.createSession(user.getId());
        return new Identity(user.getId(), session.getId(), jwt.issue(user.getId(), session.getId()));
    }

    private Csrf csrf() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        return new Csrf(response.getCookie("__Host-booking_csrf"), json.readTree(response.getContentAsString()).get("token").asText());
    }
    private Cookie access(String value) { return new Cookie(AuthenticationCookies.ACCESS, value); }
    private record Identity(UUID account, UUID session, String token) { }
    private record Csrf(Cookie cookie, String value) { }
}
