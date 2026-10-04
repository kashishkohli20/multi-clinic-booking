package com.booking.bookingsystem.dto;

import com.booking.bookingsystem.entity.Doctor;

import java.time.Instant;
import java.util.UUID;

public record DoctorProfileResponse(UUID id, String name, String description,
                                    Instant createdAt, Instant updatedAt) {
    public static DoctorProfileResponse from(Doctor doctor) {
        return new DoctorProfileResponse(doctor.getId(), doctor.getName(), doctor.getDescription(),
                doctor.getCreatedAt(), doctor.getUpdatedAt());
    }
}
