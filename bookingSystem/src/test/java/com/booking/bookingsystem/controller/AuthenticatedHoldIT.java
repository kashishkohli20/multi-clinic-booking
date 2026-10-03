package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.*;
import com.booking.bookingsystem.repository.*;
import com.booking.bookingsystem.service.*;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthenticatedHoldIT {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String PASSWORD = "Authenticated hold passphrase!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PatientRegistrationService registration;
    @Autowired LoginService logins;
    @Autowired AccessTokenService jwt;
    @Autowired PatientRepository patients;
    @Autowired AppUserRepository users;
    @Autowired ClinicRepository clinics;
    @Autowired DoctorRepository doctors;
    @Autowired SlotRepository slots;
    @Autowired HoldRepository holds;
    @Autowired java.time.Duration holdDuration;
    @MockitoBean Clock clock;
    @MockitoSpyBean LoginSessionService sessions;
    private final List<Patient> createdPatients = new ArrayList<>();
    private Patient patient;
    private LoginService.Result login;
    private Slot slot;
    private Csrf csrf;

    @BeforeEach
    void setup() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        patient = patient();
        login = logins.login(patient.getAppUser().getEmail(), PASSWORD);
        slot = new Slot();
        slot.setClinic(clinics.saveAndFlush(new Clinic()));
        slot.setDoctorId(doctors.saveAndFlush(new Doctor()).getId());
        slot.setStartTime(NOW.plusSeconds(3600));
        slot.setEndTime(NOW.plusSeconds(7200));
        slot.setTimezone("UTC");
        slot.setStatus(Slot.Status.OPEN);
        slot = slots.saveAndFlush(slot);
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        csrf = new Csrf(response.getCookie("__Host-booking_csrf"), json.readTree(response.getContentAsString()).get("token").asText());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from appointment where booked_slot_id = ?", slot.getId());
        jdbc.update("delete from hold where slot_id = ?", slot.getId());
        jdbc.update("delete from slot where id = ?", slot.getId());
        jdbc.update("delete from doctor where id = ?", slot.getDoctorId());
        jdbc.update("delete from clinic where id = ?", slot.getClinic().getId());
        for (Patient p : createdPatients) {
            UUID userId = p.getAppUser().getId();
            jdbc.update("delete from refresh_token where session_id in (select id from login_session where user_id = ?)", userId);
            jdbc.update("delete from login_session where user_id = ?", userId);
            jdbc.update("delete from patient where id = ?", p.getId());
            jdbc.update("delete from app_user where id = ?", userId);
        }
    }

    @Test
    void patientIsDerivedFromAccountAndSuccessfulHoldRecordsActivity() throws Exception {
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var response = hold(body(slot.getId()), login.access().value());
        assertEquals(201, response.getStatus());
        var result = json.readTree(response.getContentAsString());
        var saved = holds.findById(UUID.fromString(result.get("id").asText())).orElseThrow();
        assertEquals(patient.getId(), saved.getHoldById());
        assertNotEquals(patient.getAppUser().getId(), saved.getHoldById());
        assertEquals(NOW.plusSeconds(60).plus(holdDuration), saved.getExpiresAt());
        assertEquals(Slot.Status.HELD, slots.findById(slot.getId()).orElseThrow().getStatus());
        assertEquals(Timestamp.from(NOW.plusSeconds(60)), session().get("last_activity_at"));
        assertEquals(Timestamp.from(login.refresh().expiresAt()), session().get("expires_at"));
        mvc.perform(post("/api/v1/confirm").cookie(csrf.cookie(), access(login.access().value()))
                        .header("X-CSRF-TOKEN", csrf.value()).contentType("application/json")
                        .content("{\"holdId\":\"" + saved.getId() + "\"}"))
                .andExpect(status().isForbidden());
        assertEquals(Hold.Status.ACTIVE, holds.findById(saved.getId()).orElseThrow().getStatus());
    }

    @Test
    void callerSuppliedPatientAccountAndSessionCannotImpersonateAnotherPatient() throws Exception {
        Patient other = patient();
        var response = hold(json.writeValueAsString(Map.of("slotId", slot.getId(), "patientId", other.getId(),
                "accountId", other.getAppUser().getId(), "sessionId", UUID.randomUUID(), "roles", List.of("CLINIC_ADMIN"))), login.access().value());
        assertEquals(201, response.getStatus());
        assertEquals(patient.getId().toString(), json.readTree(response.getContentAsString()).get("holdById").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "malformed", "expired-jwt", "revoked", "disabled", "deleted", "idle", "absolute", "wrong-owner"})
    void invalidAuthenticationReturns401AndChangesNothing(String reason) throws Exception {
        String token = login.access().value();
        switch (reason) {
            case "anonymous" -> token = null;
            case "malformed" -> token = "broken";
            case "expired-jwt" -> when(clock.instant()).thenReturn(NOW.plusSeconds(900));
            case "revoked" -> sessions.revokeSession(login.refresh().sessionId(), login.accountId());
            case "disabled" -> jdbc.update("update app_user set enabled = false where id = ?", login.accountId());
            case "deleted" -> users.deleteById(login.accountId());
            case "wrong-owner" -> token = jwt.issue(UUID.randomUUID(), login.refresh().sessionId());
            case "idle", "absolute" -> {
                when(clock.instant()).thenReturn(NOW.plusSeconds(reason.equals("idle") ? 1800 : 8 * 3600));
                token = jwt.issue(login.accountId(), login.refresh().sessionId());
            }
        }
        var before = session();
        assertEquals(401, hold(body(slot.getId()), token).getStatus());
        assertUnchanged(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted-profile", "unlinked-profile"})
    void accountWithoutActivePatientProfileGets403WithoutActivityOrHold(String reason) throws Exception {
        if (reason.equals("deleted-profile")) patients.deleteById(patient.getId());
        else jdbc.update("update patient set user_id = null where id = ?", patient.getId());
        var before = session();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertEquals(403, hold(json.writeValueAsString(Map.of("slotId", slot.getId(), "patientId", patient.getId())), login.access().value()).getStatus());
        assertUnchanged(before);
    }

    @Test
    void missingCsrfCannotCreateHold() throws Exception {
        var before = session();
        mvc.perform(post("/api/v1/slots/hold").cookie(access(login.access().value()))
                .contentType("application/json").content(body(slot.getId()))).andExpect(status().isForbidden());
        assertUnchanged(before);
    }

    @Test
    void missingSlotAndUnknownSlotDoNotRecordActivity() throws Exception {
        var before = session();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertEquals(400, hold("{}", login.access().value()).getStatus());
        assertEquals(404, hold(body(UUID.randomUUID()), login.access().value()).getStatus());
        assertUnchanged(before);
    }

    @Test
    void conflictRollsBackActivityAndDoesNotCreateAnotherHold() throws Exception {
        assertEquals(201, hold(body(slot.getId()), login.access().value()).getStatus());
        var before = session();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertEquals(409, hold(body(slot.getId()), login.access().value()).getStatus());
        assertEquals(before, session());
        assertEquals(1, jdbc.queryForObject("select count(*) from hold where slot_id = ?", Integer.class, slot.getId()));
    }

    @Test
    void logoutAfterFilterValidationStillPreventsHoldAndReturns401() throws Exception {
        doAnswer(invocation -> {
            // Separate thread/transaction commits revocation after the filter checked the session,
            // before the booking boundary takes the same session lock.
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> sessions.revokeSession(login.refresh().sessionId(), login.accountId())).get(10, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).when(sessions).recordActivity(login.refresh().sessionId(), login.accountId());
        assertEquals(401, hold(body(slot.getId()), login.access().value()).getStatus());
        assertNotNull(session().get("revoked_at"));
        assertNoHold();
    }

    @Test
    void expiryAfterFilterValidationCannotBeRevivedByBookingActivity() throws Exception {
        doAnswer(invocation -> {
            when(clock.instant()).thenReturn(NOW.plusSeconds(1800));
            return invocation.callRealMethod();
        }).when(sessions).recordActivity(login.refresh().sessionId(), login.accountId());
        var before = session();
        assertEquals(401, hold(body(slot.getId()), login.access().value()).getStatus());
        assertUnchanged(before);
    }

    private Patient patient() {
        var p = registration.createPatient("Patient test", UUID.randomUUID() + "@example.com", PASSWORD);
        createdPatients.add(p);
        return p;
    }
    private String body(UUID slotId) { return "{\"slotId\":\"" + slotId + "\"}"; }
    private Cookie access(String value) { return new Cookie("booking_access", value); }
    private MockHttpServletResponse hold(String body, String token) throws Exception {
        var request = post("/api/v1/slots/hold").cookie(csrf.cookie()).header("X-CSRF-TOKEN", csrf.value())
                .contentType("application/json").content(body);
        if (token != null) request.cookie(access(token));
        return mvc.perform(request).andReturn().getResponse();
    }
    private Map<String, Object> session() {
        return jdbc.queryForMap("select * from login_session where id = ?", login.refresh().sessionId());
    }
    private void assertUnchanged(Map<String, Object> before) {
        assertEquals(before, session());
        assertNoHold();
    }
    private void assertNoHold() {
        assertEquals(Slot.Status.OPEN, slots.findById(slot.getId()).orElseThrow().getStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from hold where slot_id = ?", Integer.class, slot.getId()));
    }
    private record Csrf(Cookie cookie, String value) { }
}
