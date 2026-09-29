package com.booking.bookingsystem.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SlotHoldRequest(
    @NotNull(message = "Slot is required") UUID slotId,
    @NotNull(message = "Patient is required") UUID patientId
) {}
