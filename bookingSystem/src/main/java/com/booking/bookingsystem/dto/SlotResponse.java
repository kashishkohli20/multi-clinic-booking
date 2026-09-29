package com.booking.bookingsystem.dto;

import com.booking.bookingsystem.entity.Slot;
import java.time.Instant;
import java.util.UUID;

public record SlotResponse(UUID id, UUID doctorId, UUID clinicId, Instant startTime, Instant endTime,
                           String timezone, String status) {
    public static SlotResponse from(Slot slot) {
        return new SlotResponse(slot.getId(), slot.getDoctorId(), slot.getClinic().getId(),
                slot.getStartTime(), slot.getEndTime(), slot.getTimezone(), slot.getStatus().name());
    }
}
