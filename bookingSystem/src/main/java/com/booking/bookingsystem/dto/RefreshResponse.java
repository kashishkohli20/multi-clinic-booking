package com.booking.bookingsystem.dto;

import java.time.Instant;
import java.util.UUID;

public record RefreshResponse(UUID accountId, Instant accessExpiresAt, Instant sessionExpiresAt) { }
