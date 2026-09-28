package com.booking.bookingsystem.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class SlotHoldRequest {
    @NotNull(message = "Slot is required")
    private UUID slotId;
    @NotNull(message = "Patient is required")
    private UUID patientId;
}
