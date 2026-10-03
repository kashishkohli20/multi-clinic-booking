package com.booking.bookingsystem.dto;

import java.time.Instant;
import java.util.UUID;

public record LoginResponse(UUID accountId, Instant accessExpiresAt, Instant sessionExpiresAt) { }
