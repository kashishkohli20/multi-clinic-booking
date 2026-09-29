package com.booking.bookingsystem.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ConfirmationRequest(
    @NotNull(message = "Hold is required") UUID holdId
) {}
