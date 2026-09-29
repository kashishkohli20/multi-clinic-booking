package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.service.BookingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@WebMvcTest(BookingController.class) // Boots only the web layer and advice mechanisms
public class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingService bookingService; // Spring mocks the service bean within the web context

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
                .andExpect(jsonPath("$.errors.patientId").value("Patient is required"));
        verifyNoInteractions(bookingService);
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
        UUID patientId = UUID.randomUUID();
        String body;
        var failure = new ObjectOptimisticLockingFailureException("internal entity details", resourceId);
        if (path.endsWith("/hold")) {
            when(bookingService.holdSlot(resourceId, patientId)).thenThrow(failure);
            body = "{\"slotId\":\"%s\",\"patientId\":\"%s\"}".formatted(resourceId, patientId);
        } else {
            when(bookingService.confirmBooking(resourceId)).thenThrow(failure);
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
}
