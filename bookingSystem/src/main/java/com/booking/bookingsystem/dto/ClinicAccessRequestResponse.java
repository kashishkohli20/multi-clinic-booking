package com.booking.bookingsystem.dto;

import com.booking.bookingsystem.entity.DoctorClinicAccessRequest;
import java.time.Instant;
import java.util.UUID;

public record ClinicAccessRequestResponse(UUID id, UUID clinicId, String status,
                                         Instant requestedAt, Instant decidedAt) {
    public static ClinicAccessRequestResponse from(DoctorClinicAccessRequest request) {
        return new ClinicAccessRequestResponse(request.getId(), request.getClinicId(), request.getStatus().name(),
                request.getRequestedAt(), request.getDecidedAt());
    }
}
