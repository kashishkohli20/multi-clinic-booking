package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.*;
import com.booking.bookingsystem.repository.*;
import com.booking.bookingsystem.config.ReservationConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ReservationDeadlineIT {
    @Autowired HoldRepository holds;
    @Autowired SlotRepository slots;
    @Autowired ClinicRepository clinics;

    @Test
    void cleanupExpiresBeforeAndAtDeadlineButLeavesFutureHold() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        Clinic clinic = new Clinic();
        clinic.setName("Deadline test clinic");
        clinic = clinics.saveAndFlush(clinic);
        Hold[] reservations = new Hold[3];
        Slot[] reservedSlots = new Slot[3];
        for (int i = 0; i < 3; i++) {
            Slot slot = new Slot();
            slot.setClinic(clinic);
            slot.setStatus(Slot.Status.HELD);
            slot.setStartTime(now.plusSeconds(3600));
            slot.setEndTime(now.plusSeconds(7200));
            reservedSlots[i] = slots.saveAndFlush(slot);
            Hold hold = new Hold();
            hold.setSlotId(slot.getId());
            hold.setHoldById(UUID.randomUUID());
            hold.setStatus(Hold.Status.ACTIVE);
            hold.setExpiresAt(now.plusSeconds(i - 1));
            reservations[i] = holds.saveAndFlush(hold);
        }
        new ExpirationCleanupWorker(holds, slots, Clock.fixed(now, ZoneOffset.UTC)).cleanupExpiredHolds();
        holds.flush();
        slots.flush();
        for (int i = 0; i < 3; i++) {
            assertEquals(i < 2 ? Hold.Status.EXPIRED : Hold.Status.ACTIVE, reservations[i].getStatus());
            assertEquals(i < 2 ? Slot.Status.OPEN : Slot.Status.HELD, reservedSlots[i].getStatus());
        }
    }

    @Test
    void holdDurationMustBePositive() {
        var configuration = new ReservationConfiguration();
        assertThrows(IllegalArgumentException.class, () -> configuration.holdDuration(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> configuration.holdDuration(Duration.ofSeconds(-1)));
        assertEquals(Duration.ofSeconds(30), configuration.holdDuration(Duration.ofSeconds(30)));
    }
}
