package com.booking.bookingsystem.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PatientRegistrationRequestTest {
    @Test
    void toStringRedactsRegistrationDetails() {
        var request = new PatientRegistrationRequest("Alice", "alice@example.com", "A sample passphrase!");
        assertEquals("PatientRegistrationRequest[REDACTED]", request.toString());
        assertEquals("A sample passphrase!", request.password());
    }
}
