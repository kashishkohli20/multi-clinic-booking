package com.booking.bookingsystem.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class BookingRequest {
    @NotNull(message = "Slot is required")
    private UUID slotId;
    @NotNull(message = "Clinic is required")
    private UUID clinicId;
    @NotNull(message = "Patient is required")
    private UUID patientId;
}
