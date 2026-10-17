package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.*;
import com.booking.bookingsystem.repository.*;
import com.booking.bookingsystem.service.*;
import com.booking.bookingsystem.service.exception.ResourceNotFoundException;
import com.booking.bookingsystem.util.MembershipRole;
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
import java.util.*;
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
class ClinicAccessRequestHttpIT {
    private static final String PATH = "/api/v1/doctors/me/clinic-requests";
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;
    @Autowired DoctorRepository doctors;
    @Autowired ClinicRepository clinics;
    @Autowired ClinicMembershipRepository memberships;
    @Autowired DoctorClinicAccessRequestRepository requests;
    @Autowired DoctorClinicAccessRequestService service;
    @Autowired AccessTokenService jwt;
    @MockitoSpyBean LoginSessionService sessions;
    @MockitoSpyBean AuthenticatedClinicAccessRequestService boundary;
    @MockitoBean Clock clock;
    private final List<Caller> callers = new ArrayList<>();
    private final List<UUID> clinicIds = new ArrayList<>();
    private Caller caller;
    private UUID clinic;
    private Cookie csrfCookie;
    private String csrfToken;

    @BeforeEach
    void setup() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        caller = caller();
        clinic = clinic();
        var csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        csrfCookie = csrf.getCookie("__Host-booking_csrf");
        csrfToken = json.readTree(csrf.getContentAsString()).get("token").asText();
    }

    @AfterEach
    void cleanup() {
        for (Caller c : callers) {
            jdbc.update("delete from doctor_clinic_access_request where doctor_id=?", c.doctorId());
            jdbc.update("delete from clinic_membership where user_id=?", c.accountId());
            jdbc.update("delete from login_session where user_id=?", c.accountId());
            jdbc.update("delete from doctor where id=?", c.doctorId());
        }
        for (Caller c : callers) jdbc.update("delete from app_user where id=?", c.accountId());
        for (UUID id : clinicIds) jdbc.update("delete from clinic where id=?", id);
    }

    @Test
    void submitsPendingRequestAndReadsSafeDtoWithoutGrantingMembership() throws Exception {
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var response = submit(body(clinic), caller.token());
        assertEquals(201, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        var dto = json.readTree(response.getContentAsString());
        assertEquals(5, dto.size());
        UUID id = UUID.fromString(dto.get("id").asText());
        assertEquals(clinic.toString(), dto.get("clinicId").asText());
        assertEquals("PENDING", dto.get("status").asText());
        assertEquals(NOW.plusSeconds(60).toString(), dto.get("requestedAt").asText());
        assertTrue(dto.get("decidedAt").isNull());
        assertEquals(PATH + "/" + id, response.getHeader("Location"));
        assertEquals(caller.doctorId(), requests.findById(id).orElseThrow().getDoctorId());
        assertEquals(0, membershipCount());
        assertEquals(Timestamp.from(NOW.plusSeconds(60)), session(caller).get("last_activity_at"));
        var before = session(caller);
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        var read = read(id, caller.token());
        assertEquals(200, read.getStatus());
        assertEquals("no-store", read.getHeader("Cache-Control"));
        assertEquals(dto, json.readTree(read.getContentAsString()));
        assertEquals(before, session(caller));
    }

    @Test
    void suppliedOwnerStatusAndDecisionFieldsCannotControlRequest() throws Exception {
        Caller other = caller();
        var response = submit(json.writeValueAsString(Map.of("clinicId", clinic, "doctorId", other.doctorId(),
                "accountId", other.accountId(), "sessionId", other.sessionId(), "status", "APPROVED",
                "decidedById", other.accountId(), "role", "CLINIC_ADMIN", "requestedAt", "2000-01-01T00:00:00Z")), caller.token());
        assertEquals(201, response.getStatus());
        UUID id = UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());
        var stored = requests.findById(id).orElseThrow();
        assertEquals(caller.doctorId(), stored.getDoctorId());
        assertEquals(DoctorClinicAccessRequest.Status.PENDING, stored.getStatus());
        assertEquals(NOW, stored.getRequestedAt());
        assertNull(stored.getDecidedById());
        assertEquals(0, membershipCount());
    }

    @Test
    void anotherDoctorsRequestAndUnknownRequestReturnSame404() throws Exception {
        var other = caller();
        var foreign = service.submit(other.accountId(), clinic);
        var denied = read(foreign.getId(), caller.token());
        var missing = read(UUID.randomUUID(), caller.token());
        assertEquals(404, denied.getStatus());
        assertEquals(404, missing.getStatus());
        assertEquals(json.readTree(missing.getContentAsString()).get("message"), json.readTree(denied.getContentAsString()).get("message"));
        assertThrows(ResourceNotFoundException.class, () -> service.readOwn(caller.accountId(), foreign.getId()));
        mvc.perform(get(PATH + "/" + foreign.getId()).cookie(access(caller.token())).param("doctorId", other.doctorId().toString()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "deleted", "unlinked"})
    void requiresOwnActiveDoctorProfileForBothOperations(String state) throws Exception {
        var request = service.submit(caller.accountId(), clinic);
        if (state.equals("missing")) {
            jdbc.update("delete from doctor_clinic_access_request where id=?", request.getId());
            jdbc.update("delete from doctor where id=?", caller.doctorId());
        } else if (state.equals("deleted")) doctors.deleteById(caller.doctorId());
        else jdbc.update("update doctor set user_id=null where id=?", caller.doctorId());
        var before = session(caller);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertEquals(403, submit(body(clinic), caller.token()).getStatus());
        assertEquals(403, read(request.getId(), caller.token()).getStatus());
        assertEquals(before, session(caller));
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "malformed", "expired-jwt", "disabled", "deleted", "revoked", "idle", "absolute", "wrong-owner"})
    void invalidAuthenticationReturns401WithoutWrites(String state) throws Exception {
        String token = caller.token();
        switch (state) {
            case "anonymous" -> token = null;
            case "malformed" -> token = "broken";
            case "expired-jwt" -> when(clock.instant()).thenReturn(NOW.plusSeconds(900));
            case "disabled" -> jdbc.update("update app_user set enabled=false where id=?", caller.accountId());
            case "deleted" -> users.deleteById(caller.accountId());
            case "revoked" -> sessions.revokeSession(caller.sessionId(), caller.accountId());
            case "wrong-owner" -> token = jwt.issue(UUID.randomUUID(), caller.sessionId());
            case "idle", "absolute" -> {
                when(clock.instant()).thenReturn(NOW.plusSeconds(state.equals("idle") ? 1800 : 8 * 3600));
                token = jwt.issue(caller.accountId(), caller.sessionId());
            }
        }
        var before = session(caller);
        assertEquals(401, submit(body(clinic), token).getStatus());
        assertEquals(401, read(UUID.randomUUID(), token).getStatus());
        assertEquals(before, session(caller));
        assertEquals(0, requestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "deleted"})
    void missingClinicReturns404AndRollsBackActivity(String state) throws Exception {
        UUID target = state.equals("unknown") ? UUID.randomUUID() : clinic;
        if (state.equals("deleted")) clinics.deleteById(clinic);
        var before = session(caller);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertEquals(404, submit(body(target), caller.token()).getStatus());
        assertEquals(before, session(caller));
        assertEquals(0, requestCount());
    }

    @Test
    void duplicatePendingRequestReturns409WithoutOverwritingOrRecordingActivity() throws Exception {
        var original = service.submit(caller.accountId(), clinic);
        var before = session(caller);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertEquals(409, submit(body(clinic), caller.token()).getStatus());
        assertEquals(1, requestCount());
        assertEquals(NOW, requests.findById(original.getId()).orElseThrow().getRequestedAt());
        assertEquals(before, session(caller));
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "deleted", "inactive", "clinic-admin", "other-clinic"})
    void assignmentRulesAreScopedToDoctorRoleAndTargetClinic(String state) throws Exception {
        var membership = new ClinicMembership();
        membership.setAppUser(users.findById(caller.accountId()).orElseThrow());
        membership.setClinic(clinics.findById(state.equals("other-clinic") ? clinic() : clinic).orElseThrow());
        membership.setRole(state.equals("clinic-admin") ? MembershipRole.CLINIC_ADMIN : MembershipRole.DOCTOR);
        membership.setActive(!state.equals("inactive"));
        memberships.saveAndFlush(membership);
        if (state.equals("deleted")) memberships.deleteById(membership.getId());
        var before = jdbc.queryForMap("select * from clinic_membership where id=?", membership.getId());
        var activity = session(caller);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        boolean blocked = state.equals("active") || state.equals("deleted");
        assertEquals(blocked ? 409 : 201, submit(body(clinic), caller.token()).getStatus());
        assertEquals(before, jdbc.queryForMap("select * from clinic_membership where id=?", membership.getId()));
        assertEquals(blocked ? 0 : 1, requestCount());
        if (blocked) assertEquals(activity, session(caller));
    }

    @Test
    void rejectedHistoryIsRetainedAndAnotherClinicCanReceiveAnIndependentRequest() throws Exception {
        var prior = service.submit(caller.accountId(), clinic);
        prior.setStatus(DoctorClinicAccessRequest.Status.REJECTED);
        prior.setDecidedById(caller.accountId()); // Persistence fixture, not an authorized decision API.
        prior.setDecidedAt(NOW);
        requests.saveAndFlush(prior);
        assertEquals(201, submit(body(clinic), caller.token()).getStatus());
        assertEquals(201, submit(body(clinic()), caller.token()).getStatus());
        assertEquals(3, requestCount());
        clinics.deleteById(clinic);
        var history = read(prior.getId(), caller.token());
        assertEquals(200, history.getStatus());
        var dto = json.readTree(history.getContentAsString());
        assertEquals("REJECTED", dto.get("status").asText());
        assertFalse(dto.has("decidedById"));
    }

    @Test
    void badInputsCsrfAndUntrustedOriginDoNotCreateRequests() throws Exception {
        var before = session(caller);
        for (String input : List.of("{}", "null", "{bad json", "{\"clinicId\":\"invalid\"}")) {
            assertEquals(400, submit(input, caller.token()).getStatus());
        }
        mvc.perform(post(PATH).cookie(access(caller.token())).contentType("application/json").content(body(clinic)))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH).cookie(access(caller.token()), csrfCookie).header("X-CSRF-TOKEN", "invalid")
                .contentType("application/json").content(body(clinic))).andExpect(status().isForbidden());
        mvc.perform(post(PATH).cookie(access(caller.token()), csrfCookie).header("X-CSRF-TOKEN", csrfToken)
                .header("Origin", "https://untrusted.example").contentType("application/json").content(body(clinic)))
                .andExpect(status().isForbidden());
        assertEquals(0, requestCount());
        assertEquals(before, session(caller));
    }

    @Test
    void failureAfterInsertRollsBackRequestAndActivity() throws Exception {
        // The spy executes inside the outer service transaction managed by Spring.
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataIntegrityViolationException("Simulated failure after insert");
        }).when(boundary).submit(any(), any(), any());
        var before = session(caller);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var response = submit(body(clinic), caller.token());
        assertEquals(500, response.getStatus());
        assertFalse(response.getContentAsString().contains("Simulated"));
        assertEquals(0, requestCount());
        assertEquals(before, session(caller));
    }

    @ParameterizedTest
    @ValueSource(strings = {"submit-revoke", "read-revoke", "submit-disable", "read-disable", "submit-expire", "read-expire"})
    void sessionIsCheckedAgainAfterFilterValidation(String state) throws Exception {
        var first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            var valid = invocation.callRealMethod();
            if (first.compareAndSet(true, false)) {
                if (state.endsWith("expire")) when(clock.instant()).thenReturn(NOW.plusSeconds(1800));
                else try (var executor = Executors.newSingleThreadExecutor()) {
                    executor.submit(() -> {
                        if (state.endsWith("revoke")) sessions.revokeSession(caller.sessionId(), caller.accountId());
                        else jdbc.update("update app_user set enabled=false where id=?", caller.accountId());
                    }).get(10, TimeUnit.SECONDS);
                }
            }
            return valid;
        }).when(sessions).requireValidSession(caller.sessionId(), caller.accountId());
        var response = state.startsWith("submit") ? submit(body(clinic), caller.token()) : read(UUID.randomUUID(), caller.token());
        assertEquals(401, response.getStatus());
        assertEquals(0, requestCount());
        assertEquals(Timestamp.from(NOW), session(caller).get("last_activity_at"));
    }

    @Test
    void concurrentSessionsProduceOne201AndOne409() throws Exception {
        var secondSession = sessions.createSession(caller.accountId());
        String token = jwt.issue(caller.accountId(), secondSession.getId());
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return submit(body(clinic), caller.token()).getStatus(); });
            var second = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return submit(body(clinic), token).getStatus(); });
            int a = first.get(20, TimeUnit.SECONDS), b = second.get(20, TimeUnit.SECONDS);
            assertEquals(List.of(201, 409), java.util.stream.Stream.of(a, b).sorted().toList());
            assertEquals(1, requestCount());
            assertEquals(0, membershipCount());
            assertEquals(Timestamp.from(a == 201 ? NOW.plusSeconds(60) : NOW), session(caller).get("last_activity_at"));
            assertEquals(Timestamp.from(b == 201 ? NOW.plusSeconds(60) : NOW), jdbc.queryForMap("select * from login_session where id=?", secondSession.getId()).get("last_activity_at"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Caller caller() {
        var user = new AppUser();
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setEnabled(true);
        users.saveAndFlush(user);
        var doctor = new Doctor();
        doctor.setAppUser(user);
        doctor.setName("Test doctor");
        doctors.saveAndFlush(doctor);
        var session = sessions.createSession(user.getId());
        var result = new Caller(user.getId(), doctor.getId(), session.getId(), jwt.issue(user.getId(), session.getId()));
        callers.add(result);
        return result;
    }
    private UUID clinic() {
        var c = clinics.saveAndFlush(new Clinic());
        clinicIds.add(c.getId());
        return c.getId();
    }
    private String body(UUID id) { return "{\"clinicId\":\"" + id + "\"}"; }
    private Cookie access(String token) { return new Cookie("booking_access", token); }
    private MockHttpServletResponse submit(String body, String token) throws Exception {
        var request = post(PATH).cookie(csrfCookie).header("X-CSRF-TOKEN", csrfToken).contentType("application/json").content(body);
        if (token != null) request.cookie(access(token));
        return mvc.perform(request).andReturn().getResponse();
    }
    private MockHttpServletResponse read(UUID id, String token) throws Exception {
        var request = get(PATH + "/" + id);
        if (token != null) request.cookie(access(token));
        return mvc.perform(request).andReturn().getResponse();
    }
    private Map<String, Object> session(Caller c) { return jdbc.queryForMap("select * from login_session where id=?", c.sessionId()); }
    private int requestCount() { return jdbc.queryForObject("select count(*) from doctor_clinic_access_request where doctor_id=?", Integer.class, caller.doctorId()); }
    private int membershipCount() { return jdbc.queryForObject("select count(*) from clinic_membership where user_id=?", Integer.class, caller.accountId()); }
    private record Caller(UUID accountId, UUID doctorId, UUID sessionId, String token) { }
}
