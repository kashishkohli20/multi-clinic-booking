package com.booking.bookingsystem.dto;

import com.booking.bookingsystem.entity.Hold;
import java.time.Instant;
import java.util.UUID;

public record HoldResponse(UUID id, UUID slotId, UUID holdById, String status, Instant expiresAt) {
    public static HoldResponse from(Hold hold) {
        return new HoldResponse(hold.getId(), hold.getSlotId(), hold.getHoldById(),
                hold.getStatus().name(), hold.getExpiresAt());
    }
}
