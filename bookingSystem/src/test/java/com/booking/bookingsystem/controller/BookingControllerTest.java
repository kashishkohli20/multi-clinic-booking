package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.service.BookingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    @DisplayName("Should return 400 Bad Request when request body is completely empty")
    void bookSlot_ValidationFailure() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")) // Sending empty JSON
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.slotId").value("Slot is required"))
                .andExpect(jsonPath("$.errors.patientId").value("Patient is required"));
    }
}