package com.booking.bookingsystem.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

// Account/profile IDs, clinic membership, role and account state are never inputs.
@JsonIgnoreProperties(ignoreUnknown = true)
public record DoctorRegistrationRequest(@NotBlank String name, @NotBlank String email,
                                        @NotBlank String password, String description) {
    @Override
    public String toString() {
        return "DoctorRegistrationRequest[REDACTED]";
    }
}
