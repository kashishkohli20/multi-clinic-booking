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
class AuthenticatedConfirmationIT {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String PASSWORD = "Authenticated hold passphrase!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PatientRegistrationService registration;
    @Autowired LoginService logins;
    @Autowired PatientRepository patients;
    @Autowired AppUserRepository users;
    @Autowired ClinicRepository clinics;
    @Autowired DoctorRepository doctors;
    @Autowired SlotRepository slots;
    @Autowired HoldRepository holds;
    @MockitoBean Clock clock;
    @MockitoSpyBean LoginSessionService sessions;
    private final List<Patient> createdPatients = new ArrayList<>();
    private Patient patient;
    private LoginService.Result login;
    private Slot slot;
    private Csrf csrf;
    private Hold reserved;
    @Autowired BookingService booking;

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
        reserved = booking.holdSlot(slot.getId(), patient.getId());
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
    void ownerConfirmsAndActorIsAccountNotPatientOrCallerSuppliedId() throws Exception {
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        var response = confirm(json.writeValueAsString(Map.of("holdId", reserved.getId(),
                "patientId", UUID.randomUUID(), "bookedById", UUID.randomUUID(), "accountId", UUID.randomUUID())), login.access().value());
        assertEquals(201, response.getStatus());
        var result = json.readTree(response.getContentAsString());
        assertEquals(patient.getId().toString(), result.get("patientId").asText());
        assertEquals(login.accountId().toString(), result.get("bookedById").asText());
        assertNotEquals(patient.getId(), login.accountId());
        var saved = jdbc.queryForMap("select * from appointment where booked_slot_id = ?", slot.getId());
        assertEquals(login.accountId(), saved.get("booked_by_id"));
        assertNull(saved.get("legacy_booked_by_patient_id"));
        assertEquals(Hold.Status.CONFIRMED, holds.findById(reserved.getId()).orElseThrow().getStatus());
        assertEquals(Slot.Status.BOOKED, slots.findById(slot.getId()).orElseThrow().getStatus());
        assertEquals(Timestamp.from(NOW.plusSeconds(1)), session().get("last_activity_at"));
        assertEquals(Timestamp.from(login.refresh().expiresAt()), session().get("expires_at"));
        var before = session();
        when(clock.instant()).thenReturn(NOW.plusSeconds(2));
        assertEquals(409, confirm(body(reserved.getId()), login.access().value()).getStatus());
        assertEquals(before, session());
        assertEquals(1, appointmentCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "EXPIRED", "CONFIRMED", "CANCELED"})
    void anotherPatientCannotConfirmOrDiscoverHoldState(String state) throws Exception {
        Patient other = patient();
        var otherLogin = logins.login(other.getAppUser().getEmail(), PASSWORD);
        jdbc.update("update hold set status = ? where id = ?", state, reserved.getId());
        var holdBefore = jdbc.queryForMap("select * from hold where id = ?", reserved.getId());
        var otherBefore = jdbc.queryForMap("select * from login_session where id = ?", otherLogin.refresh().sessionId());
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        var response = confirm(json.writeValueAsString(Map.of("holdId", reserved.getId(), "patientId", patient.getId())), otherLogin.access().value());
        assertEquals(403, response.getStatus());
        assertEquals("Access denied.", json.readTree(response.getContentAsString()).get("message").asText());
        assertEquals(holdBefore, jdbc.queryForMap("select * from hold where id = ?", reserved.getId()));
        assertEquals(otherBefore, jdbc.queryForMap("select * from login_session where id = ?", otherLogin.refresh().sessionId()));
        assertEquals(Slot.Status.HELD, slots.findById(slot.getId()).orElseThrow().getStatus());
        assertEquals(0, appointmentCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "revoked", "disabled", "deleted-account", "deleted-patient", "unlinked-patient", "expired-access"})
    void invalidCallerCannotConfirmAndDoesNotRecordActivity(String reason) throws Exception {
        String token = login.access().value();
        int expected = 401;
        switch (reason) {
            case "anonymous" -> token = null;
            case "revoked" -> sessions.revokeSession(login.refresh().sessionId(), login.accountId());
            case "disabled" -> jdbc.update("update app_user set enabled = false where id = ?", login.accountId());
            case "deleted-account" -> users.deleteById(login.accountId());
            case "deleted-patient" -> { patients.deleteById(patient.getId()); expected = 403; }
            case "unlinked-patient" -> { jdbc.update("update patient set user_id = null where id = ?", patient.getId()); expected = 403; }
            case "expired-access" -> when(clock.instant()).thenReturn(NOW.plusSeconds(900));
        }
        var before = session();
        assertEquals(expected, confirm(body(reserved.getId()), token).getStatus());
        assertEquals(before, session());
        assertReservationUnchanged();
    }

    @Test
    void csrfRequiredAndUnknownOrInvalidHoldDoesNotAdvanceActivity() throws Exception {
        var before = session();
        mvc.perform(post("/api/v1/confirm").cookie(access(login.access().value()))
                .contentType("application/json").content(body(reserved.getId()))).andExpect(status().isForbidden());
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        assertEquals(400, confirm("{}", login.access().value()).getStatus());
        assertEquals(404, confirm(body(UUID.randomUUID()), login.access().value()).getStatus());
        assertEquals(before, session());
        assertReservationUnchanged();
    }

    @Test
    void ownerCannotConfirmAtReservationDeadline() throws Exception {
        var before = session();
        when(clock.instant()).thenReturn(reserved.getExpiresAt());
        assertEquals(409, confirm(body(reserved.getId()), login.access().value()).getStatus());
        assertEquals(before, session());
        assertReservationUnchanged();
    }

    @Test
    void revocationBetweenFilterAndTransactionPreventsConfirmation() throws Exception {
        doAnswer(invocation -> {
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> sessions.revokeSession(login.refresh().sessionId(), login.accountId())).get(10, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).when(sessions).recordActivity(login.refresh().sessionId(), login.accountId());
        assertEquals(401, confirm(body(reserved.getId()), login.access().value()).getStatus());
        assertNotNull(session().get("revoked_at"));
        assertReservationUnchanged();
    }

    private Patient patient() {
        var p = registration.createPatient("Confirmation test", UUID.randomUUID() + "@example.com", PASSWORD);
        createdPatients.add(p);
        return p;
    }
    private String body(UUID holdId) { return "{\"holdId\":\"" + holdId + "\"}"; }
    private Cookie access(String value) { return new Cookie("booking_access", value); }
    private MockHttpServletResponse confirm(String body, String token) throws Exception {
        var request = post("/api/v1/confirm").cookie(csrf.cookie()).header("X-CSRF-TOKEN", csrf.value())
                .contentType("application/json").content(body);
        if (token != null) request.cookie(access(token));
        return mvc.perform(request).andReturn().getResponse();
    }
    private Map<String, Object> session() {
        return jdbc.queryForMap("select * from login_session where id = ?", login.refresh().sessionId());
    }
    private int appointmentCount() {
        return jdbc.queryForObject("select count(*) from appointment where booked_slot_id = ?", Integer.class, slot.getId());
    }
    private void assertReservationUnchanged() {
        assertEquals(Slot.Status.HELD, slots.findById(slot.getId()).orElseThrow().getStatus());
        assertEquals(Hold.Status.ACTIVE, holds.findById(reserved.getId()).orElseThrow().getStatus());
        assertEquals(0, appointmentCount());
    }
    private record Csrf(Cookie cookie, String value) { }
}
