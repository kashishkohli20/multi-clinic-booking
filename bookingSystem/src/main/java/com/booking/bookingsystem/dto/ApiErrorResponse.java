package com.booking.bookingsystem.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class ApiErrorResponse {
    private Instant timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
}
