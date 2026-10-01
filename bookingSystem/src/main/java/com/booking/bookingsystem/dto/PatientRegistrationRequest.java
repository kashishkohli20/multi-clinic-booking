package com.booking.bookingsystem.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

// Only these three fields are inputs; role, identity and account-state fields are ignored.
@JsonIgnoreProperties(ignoreUnknown = true)
public record PatientRegistrationRequest(
    @NotBlank(message = "Patient name is required")
    String name,
    @NotBlank(message = "Patient email is required")
    String email,
    @NotBlank(message = "Patient password is required")
    String password

) {
    @Override
    public String toString() {
        return "PatientRegistrationRequest[REDACTED]";
    }
}
