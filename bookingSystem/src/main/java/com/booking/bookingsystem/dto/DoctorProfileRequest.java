package com.booking.bookingsystem.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

// Identity, clinic, role and account state cannot be set through this request.
@JsonIgnoreProperties(ignoreUnknown = true)
public record DoctorProfileRequest(@NotBlank String name, String description) {
    @Override
    public String toString() {
        return "DoctorProfileRequest[REDACTED]";
    }
}
