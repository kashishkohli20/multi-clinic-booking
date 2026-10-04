package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.repository.DoctorRepository;
import com.booking.bookingsystem.service.AccessTokenService;
import com.booking.bookingsystem.service.DoctorProfileService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DoctorProfileHttpIT {
    private static final String PATH = "/api/v1/doctors/me";
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;
    @Autowired DoctorRepository doctors;
    @Autowired AccessTokenService jwt;
    @MockitoSpyBean DoctorProfileService profiles;
    @MockitoSpyBean LoginSessionService sessions;
    @MockitoBean Clock clock;

    private final List<UUID> accounts = new ArrayList<>();
    private Caller caller;
    private Cookie csrfCookie;
    private String csrfToken;

    @BeforeEach
    void setup() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        caller = caller();
        var csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        csrfCookie = csrf.getCookie("__Host-booking_csrf");
        csrfToken = json.readTree(csrf.getContentAsString()).get("token").asText();
    }

    @AfterEach
    void cleanup() {
        for (UUID id : accounts) {
            jdbc.update("delete from doctor where user_id=?", id);
            jdbc.update("delete from login_session where user_id=?", id);
            jdbc.update("delete from app_user where id=?", id);
        }
    }

    @Test
    void createsAndReadsOwnSafeProfileWithoutPatientOrMembership() throws Exception {
        var accountBefore = jdbc.queryForMap("select * from app_user where id=?", caller.accountId());
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var created = create("{\"name\":\"  Dr Smith  \",\"description\":\"  Family medicine  \"}", caller.token());
        assertEquals(201, created.getStatus());
        assertEquals(PATH, created.getHeader("Location"));
        assertEquals("no-store", created.getHeader("Cache-Control"));
        var result = json.readTree(created.getContentAsString());
        assertEquals(5, result.size());
        assertEquals("Dr Smith", result.get("name").asText());
        assertEquals("Family medicine", result.get("description").asText());
        assertFalse(result.get("createdAt").isNull());
        assertFalse(result.get("updatedAt").isNull());
        UUID doctorId = UUID.fromString(result.get("id").asText());
        assertEquals(caller.accountId(), doctors.findById(doctorId).orElseThrow().getAppUser().getId());
        assertEquals(Timestamp.from(NOW.plusSeconds(60)), session(caller).get("last_activity_at"));
        assertEquals(accountBefore, jdbc.queryForMap("select * from app_user where id=?", caller.accountId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from patient where user_id=?", Integer.class, caller.accountId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from clinic_membership where user_id=?", Integer.class, caller.accountId()));

        var beforeRead = session(caller);
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        var read = read(caller.token());
        assertEquals(200, read.getStatus());
        assertEquals("no-store", read.getHeader("Cache-Control"));
        assertEquals(result, json.readTree(read.getContentAsString()));
        assertEquals(beforeRead, session(caller));
    }

    @Test
    void requestFieldsAndQueryParametersCannotSelectAnotherIdentityOrGrantRoles() throws Exception {
        Caller other = caller();
        UUID otherDoctor = profiles.createProfile(other.accountId(), "Other doctor", "Private biography").getId();
        String body = json.writeValueAsString(Map.of("name", "My doctor name", "accountId", other.accountId(),
                "appUser", Map.of("id", other.accountId()), "id", otherDoctor, "sessionId", other.sessionId(),
                "clinicId", UUID.randomUUID(), "role", "CLINIC_ADMIN", "active", true));
        assertEquals(201, create(body, caller.token()).getStatus());
        var read = mvc.perform(get(PATH).cookie(access(caller.token()))
                .param("accountId", other.accountId().toString()).param("doctorId", otherDoctor.toString()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("My doctor name", json.readTree(read.getContentAsString()).get("name").asText());
        assertEquals("Other doctor", doctors.findById(otherDoctor).orElseThrow().getName());
        assertEquals(0, jdbc.queryForObject("select count(*) from clinic_membership where user_id=?", Integer.class, caller.accountId()));
        mvc.perform(get("/api/v1/doctors/" + otherDoctor).cookie(access(caller.token())))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "deleted", "other-owner", "unlinked"})
    void missingOwnActiveProfileReturns404WithoutLeakingOthers(String state) throws Exception {
        Caller other = caller();
        UUID profileId = profiles.createProfile(other.accountId(), "Other person's private name", null).getId();
        if (state.equals("deleted")) {
            var own = profiles.createProfile(caller.accountId(), "Deleted name", null);
            doctors.deleteById(own.getId());
        } else if (state.equals("unlinked")) {
            jdbc.update("update doctor set user_id=null where id=?", profileId);
        }
        try {
            var before = session(caller);
            var response = read(caller.token());
            assertEquals(404, response.getStatus());
            assertEquals("Doctor profile not found.", json.readTree(response.getContentAsString()).get("message").asText());
            assertFalse(response.getContentAsString().contains("private name"));
            assertEquals(before, session(caller));
        } finally {
            jdbc.update("delete from doctor where id=?", profileId);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "malformed", "expired-jwt", "revoked", "disabled", "deleted-account", "idle", "absolute", "wrong-owner"})
    void invalidAuthenticationCannotCreateOrRead(String reason) throws Exception {
        profiles.createProfile(caller.accountId(), "Private name", null);
        String token = caller.token();
        switch (reason) {
            case "anonymous" -> token = null;
            case "malformed" -> token = "invalid";
            case "expired-jwt" -> when(clock.instant()).thenReturn(NOW.plusSeconds(900));
            case "revoked" -> sessions.revokeSession(caller.sessionId(), caller.accountId());
            case "disabled" -> jdbc.update("update app_user set enabled=false where id=?", caller.accountId());
            case "deleted-account" -> users.deleteById(caller.accountId());
            case "wrong-owner" -> token = jwt.issue(UUID.randomUUID(), caller.sessionId());
            case "idle", "absolute" -> {
                when(clock.instant()).thenReturn(NOW.plusSeconds(reason.equals("idle") ? 1800 : 8 * 3600));
                token = jwt.issue(caller.accountId(), caller.sessionId());
            }
        }
        var before = session(caller);
        assertEquals(401, create("{\"name\":\"Replacement\"}", token).getStatus());
        var response = read(token);
        assertEquals(401, response.getStatus());
        assertFalse(response.getContentAsString().contains("Private name"));
        assertEquals(before, session(caller));
        assertEquals(1, profileCount());
    }

    @Test
    void csrfIsRequiredForCreationAndBearerOrQueryTokenCannotReplaceCookie() throws Exception {
        var before = session(caller);
        mvc.perform(post(PATH).cookie(access(caller.token())).contentType("application/json")
                .content("{\"name\":\"Dr Smith\"}")).andExpect(status().isForbidden());
        mvc.perform(post(PATH).cookie(access(caller.token()), csrfCookie).header("X-CSRF-TOKEN", "wrong")
                .contentType("application/json").content("{\"name\":\"Dr Smith\"}")).andExpect(status().isForbidden());
        mvc.perform(get(PATH).header("Authorization", "Bearer " + caller.token())
                .param("access_token", caller.token())).andExpect(status().isUnauthorized());
        assertEquals(before, session(caller));
        assertEquals(0, profileCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{bad json", "{\"name\":\"   \"}", "{\"name\":\"Dr Smith\",\"description\":{}}"})
    void malformedOrMissingFieldsReturn400WithoutActivity(String body) throws Exception {
        var before = session(caller);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertEquals(400, create(body, caller.token()).getStatus());
        assertEquals(before, session(caller));
        assertEquals(0, profileCount());
    }

    @Test
    void serviceValidationRollsBackActivityAndAcceptsUnicodeLimits() throws Exception {
        var before = session(caller);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        for (String field : List.of("name", "description")) {
            var body = new java.util.HashMap<String, String>();
            body.put("name", "Dr Smith");
            body.put(field, "😀".repeat(256));
            assertEquals(400, create(json.writeValueAsString(body), caller.token()).getStatus());
            assertEquals(before, session(caller));
            assertEquals(0, profileCount());
        }
        assertEquals(201, create(json.writeValueAsString(Map.of("name", "😀".repeat(255), "description", "\u2003")), caller.token()).getStatus());
        assertNull(jdbc.queryForObject("select description from doctor where user_id=?", String.class, caller.accountId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateIncludingDeletedProfileReturns409WithoutReplacingOrRecordingActivity(boolean deleted) throws Exception {
        var profile = profiles.createProfile(caller.accountId(), "Original", "Original description");
        if (deleted) doctors.deleteById(profile.getId());
        var original = jdbc.queryForMap("select * from doctor where id=?", profile.getId());
        var before = session(caller);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var response = create("{\"name\":\"Replacement\"}", caller.token());
        assertEquals(409, response.getStatus());
        assertEquals("A Doctor profile already exists for this account.", json.readTree(response.getContentAsString()).get("message").asText());
        assertFalse(response.getContentAsString().contains("doctor_user_id_key"));
        assertEquals(before, session(caller));
        assertEquals(original, jdbc.queryForMap("select * from doctor where id=?", profile.getId()));
    }

    @Test
    void failureAfterInsertRollsBackProfileAndSessionActivity() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataIntegrityViolationException("Simulated later storage failure");
        }).when(profiles).createProfile(any(), any(), any());
        var before = session(caller);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var response = create("{\"name\":\"Dr Smith\"}", caller.token());
        assertEquals(500, response.getStatus());
        assertFalse(response.getContentAsString().contains("Simulated"));
        assertEquals(before, session(caller));
        assertEquals(0, profileCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"create-revoke", "read-revoke", "create-disable", "read-disable", "create-expire", "read-expire"})
    void serviceRevalidatesSessionAfterFilterAuthentication(String scenario) throws Exception {
        var firstCheck = new AtomicBoolean(true);
        doAnswer(invocation -> {
            var valid = invocation.callRealMethod();
            if (firstCheck.compareAndSet(true, false)) {
                if (scenario.endsWith("expire")) {
                    when(clock.instant()).thenReturn(NOW.plusSeconds(1800));
                } else {
                    try (var executor = Executors.newSingleThreadExecutor()) {
                        executor.submit(() -> {
                            if (scenario.endsWith("revoke")) sessions.revokeSession(caller.sessionId(), caller.accountId());
                            else jdbc.update("update app_user set enabled=false where id=?", caller.accountId());
                        }).get(10, TimeUnit.SECONDS);
                    }
                }
            }
            return valid;
        }).when(sessions).requireValidSession(caller.sessionId(), caller.accountId());
        var response = scenario.startsWith("create")
                ? create("{\"name\":\"Dr Smith\"}", caller.token()) : read(caller.token());
        assertEquals(401, response.getStatus());
        assertEquals(0, profileCount());
        assertEquals(Timestamp.from(NOW), session(caller).get("last_activity_at"));
    }

    @Test
    void simultaneousRequestsFromSeparateSessionsCreateExactlyOneProfile() throws Exception {
        var secondSession = sessions.createSession(caller.accountId());
        Caller second = new Caller(caller.accountId(), secondSession.getId(), jwt.issue(caller.accountId(), secondSession.getId()));
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return create("{\"name\":\"First\"}", caller.token()); });
            var other = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return create("{\"name\":\"Second\"}", second.token()); });
            var a = first.get(20, TimeUnit.SECONDS);
            var b = other.get(20, TimeUnit.SECONDS);
            assertEquals(List.of(201, 409), java.util.stream.Stream.of(a.getStatus(), b.getStatus()).sorted().toList());
            assertEquals(1, profileCount());
            assertEquals(Timestamp.from(a.getStatus() == 201 ? NOW.plusSeconds(60) : NOW), session(caller).get("last_activity_at"));
            assertEquals(Timestamp.from(b.getStatus() == 201 ? NOW.plusSeconds(60) : NOW), session(second).get("last_activity_at"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Caller caller() {
        var user = new AppUser();
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Account name");
        user.setEnabled(true);
        user = users.saveAndFlush(user);
        accounts.add(user.getId());
        var session = sessions.createSession(user.getId());
        return new Caller(user.getId(), session.getId(), jwt.issue(user.getId(), session.getId()));
    }

    private Cookie access(String token) { return new Cookie("booking_access", token); }
    private MockHttpServletResponse create(String body, String token) throws Exception {
        var request = post(PATH).cookie(csrfCookie).header("X-CSRF-TOKEN", csrfToken).contentType("application/json").content(body);
        if (token != null) request.cookie(access(token));
        return mvc.perform(request).andReturn().getResponse();
    }
    private MockHttpServletResponse read(String token) throws Exception {
        var request = get(PATH);
        if (token != null) request.cookie(access(token));
        return mvc.perform(request).andReturn().getResponse();
    }
    private Map<String, Object> session(Caller identity) {
        return jdbc.queryForMap("select * from login_session where id=?", identity.sessionId());
    }
    private int profileCount() {
        return jdbc.queryForObject("select count(*) from doctor where user_id=?", Integer.class, caller.accountId());
    }
    private record Caller(UUID accountId, UUID sessionId, String token) { }
}
