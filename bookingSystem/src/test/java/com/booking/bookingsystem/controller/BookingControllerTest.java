package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.service.BookingService;
import com.booking.bookingsystem.service.PatientBookingService;
import com.booking.bookingsystem.security.CookieAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.util.UUID;
import java.util.List;
import java.time.Instant;
import com.booking.bookingsystem.entity.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import org.springframework.test.json.JsonCompareMode;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@WebMvcTest(BookingController.class) // Boots only the web layer and advice mechanisms
@AutoConfigureMockMvc(addFilters = false) // Controller contract only; BrowserSecurityIT verifies production access rules.
@Import(BookingControllerTest.PrincipalResolution.class)
public class BookingControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class PrincipalResolution implements WebMvcConfigurer {
        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new AuthenticationPrincipalArgumentResolver());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingService bookingService; // Spring mocks the service bean within the web context

    @MockitoBean PatientBookingService patientBookings;
    private final CookieAuthenticationFilter.Principal caller =
            new CookieAuthenticationFilter.Principal(UUID.randomUUID(), UUID.randomUUID());

    @BeforeEach
    void identity() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(caller, null, List.of()));
    }

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void directBooking_IsNotAvailable() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(bookingService);
    }

    @Test
    void holdSlot_MissingFields_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/slots/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.slotId").value("Slot is required"))
                .andExpect(jsonPath("$.errors.patientId").doesNotExist());
        verifyNoInteractions(bookingService, patientBookings);
    }

    @Test
    void confirmBooking_MissingHold_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.holdId").value("Hold is required"));
        verifyNoInteractions(bookingService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/slots/hold", "/api/v1/confirm"})
    void reservation_OptimisticLockFailure_ReturnsConflict(String path) throws Exception {
        UUID resourceId = UUID.randomUUID();
        String body;
        var failure = new ObjectOptimisticLockingFailureException("internal entity details", resourceId);
        if (path.endsWith("/hold")) {
            when(patientBookings.holdSlot(resourceId, caller.accountId(), caller.sessionId())).thenThrow(failure);
            body = "{\"slotId\":\"%s\"}".formatted(resourceId);
        } else {
            when(patientBookings.confirmBooking(resourceId, caller.accountId(), caller.sessionId())).thenThrow(failure);
            body = "{\"holdId\":\"%s\"}".formatted(resourceId);
        }

        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").value(
                        "The reservation changed while processing your request. Check availability and try again."));
    }

    @Test
    void search_ReturnsOnlyPublicSlotFields() throws Exception {
        Clinic clinic = new Clinic();
        clinic.setId(UUID.randomUUID());
        clinic.setDescription("Internal clinic metadata");
        Slot slot = new Slot();
        slot.setId(UUID.randomUUID());
        slot.setDoctorId(UUID.randomUUID());
        slot.setClinic(clinic);
        slot.setStartTime(Instant.parse("2026-10-01T00:00:00Z"));
        slot.setEndTime(Instant.parse("2026-10-01T01:00:00Z"));
        slot.setTimezone("Australia/Melbourne");
        slot.setStatus(Slot.Status.OPEN);
        slot.setVersion(7L);
        slot.setCreatedAt(Instant.now());
        when(bookingService.findAvailableSlotsForDoctor("Dr Smith")).thenReturn(List.of(slot));

        mockMvc.perform(get("/api/v1/slots/search").param("doctorName", "Dr Smith"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"id":"%s","doctorId":"%s","clinicId":"%s",
                          "startTime":"2026-10-01T00:00:00Z","endTime":"2026-10-01T01:00:00Z",
                          "timezone":"Australia/Melbourne","status":"OPEN"}]
                        """.formatted(slot.getId(), slot.getDoctorId(), clinic.getId()), JsonCompareMode.STRICT));
    }

    @Test
    void hold_ReturnsOnlyPublicHoldFields() throws Exception {
        Hold hold = new Hold();
        hold.setId(UUID.randomUUID());
        hold.setSlotId(UUID.randomUUID());
        hold.setHoldById(UUID.randomUUID());
        hold.setStatus(Hold.Status.ACTIVE);
        hold.setExpiresAt(Instant.parse("2026-10-01T00:00:30Z"));
        hold.setVersion(3L);
        hold.setCreatedAt(Instant.now());
        when(patientBookings.holdSlot(hold.getSlotId(), caller.accountId(), caller.sessionId())).thenReturn(hold);

        mockMvc.perform(post("/api/v1/slots/hold").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":\"%s\"}".formatted(hold.getSlotId())))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"id":"%s","slotId":"%s","holdById":"%s","status":"ACTIVE",
                         "expiresAt":"2026-10-01T00:00:30Z"}
                        """.formatted(hold.getId(), hold.getSlotId(), hold.getHoldById()), JsonCompareMode.STRICT));
    }

    @Test
    void confirm_ReturnsOnlyPublicAppointmentFields() throws Exception {
        UUID holdId = UUID.randomUUID();
        Appointment appointment = new Appointment();
        appointment.setId(UUID.randomUUID());
        appointment.setBookedSlotId(UUID.randomUUID());
        appointment.setPatientId(UUID.randomUUID());
        appointment.setBookedById(caller.accountId());
        appointment.setStatus(Appointment.Status.CONFIRMED);
        appointment.setCreatedAt(Instant.now());
        when(patientBookings.confirmBooking(holdId, caller.accountId(), caller.sessionId())).thenReturn(appointment);

        mockMvc.perform(post("/api/v1/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"%s\"}".formatted(holdId)))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"id":"%s","bookedSlotId":"%s","patientId":"%s","bookedById":"%s",
                         "status":"CONFIRMED"}
                        """.formatted(appointment.getId(), appointment.getBookedSlotId(),
                        appointment.getPatientId(), appointment.getBookedById()), JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"uq_hold_active_slot", "uq_appointment_confirmed_slot"})
    void reservationUniquenessViolationReturns409(String constraint) throws Exception {
        UUID holdId = UUID.randomUUID();
        var databaseFailure = new org.hibernate.exception.ConstraintViolationException(
                "private database details", new java.sql.SQLException("duplicate", "23505"), constraint);
        when(patientBookings.confirmBooking(holdId, caller.accountId(), caller.sessionId())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException("private SQL", databaseFailure));
        mockMvc.perform(post("/api/v1/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"%s\"}".formatted(holdId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.path").value("/api/v1/confirm"))
                .andExpect(jsonPath("$.message").value(
                        "The slot already has an active reservation or confirmed appointment."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fk_hold_hold_by_id", "some_other_unique_constraint"})
    void unrelatedIntegrityViolationIsNotReportedAsBookingConflict(String constraint) throws Exception {
        UUID holdId = UUID.randomUUID();
        var failure = new org.hibernate.exception.ConstraintViolationException(
                "private database details", new java.sql.SQLException("failure", "23505"), constraint);
        when(patientBookings.confirmBooking(holdId, caller.accountId(), caller.sessionId())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException("private SQL", failure));
        mockMvc.perform(post("/api/v1/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"%s\"}".formatted(holdId)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unable to complete the request."));
    }
}
