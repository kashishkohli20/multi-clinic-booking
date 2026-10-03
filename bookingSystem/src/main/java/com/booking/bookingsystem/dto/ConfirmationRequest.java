package com.booking.bookingsystem.dto;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfirmationRequest(
    @NotNull(message = "Hold is required") UUID holdId
) {}
