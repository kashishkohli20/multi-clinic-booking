package com.booking.bookingsystem.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class ConfirmationRequest {
    @NotNull(message = "Hold is required")
    private UUID holdId;
}
