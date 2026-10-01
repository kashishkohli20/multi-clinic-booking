package com.booking.bookingsystem.dto;

import com.booking.bookingsystem.entity.Patient;

import java.util.UUID;

public record PatientRegistrationResponse(UUID id, UUID userId, String name) {
    public static PatientRegistrationResponse from(Patient patient) {
        return new PatientRegistrationResponse(patient.getId(), patient.getAppUser().getId(), patient.getName());
    }
}
