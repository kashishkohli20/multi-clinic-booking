package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.*;
import com.booking.bookingsystem.repository.*;
import com.booking.bookingsystem.service.exception.SlotUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {
    @Mock private PatientRepository patientRepository;
    @Mock private SlotRepository slotRepository;
    @Mock private HoldRepository holdRepository;
    @Mock private AppointmentRepository appointmentRepository;
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private BookingService bookingService;

    @BeforeEach
    void setup() {
        bookingService = new BookingService(slotRepository, holdRepository, null,
                appointmentRepository, patientRepository, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(45));
    }

    @Test
    void holdSlot_CreatesHoldWithoutAppointment() {
        UUID slotId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        Slot slot = new Slot();
        slot.setId(slotId);
        slot.setStatus(Slot.Status.OPEN);
        slot.setStartTime(NOW.plusSeconds(3600));
        when(slotRepository.findById(slotId)).thenReturn(Optional.of(slot));

        when(patientRepository.existsById(patientId)).thenReturn(true);
        Hold hold = bookingService.holdSlot(slotId, patientId);

        assertEquals(NOW.plusSeconds(45), hold.getExpiresAt());
        assertEquals(slotId, hold.getSlotId());
        assertEquals(patientId, hold.getHoldById());
        assertEquals(Hold.Status.ACTIVE, hold.getStatus());
        assertEquals(Slot.Status.HELD, slot.getStatus());
        verify(holdRepository).save(hold);
        verifyNoInteractions(appointmentRepository);
    }

    @Test
    void confirmBooking_CreatesAppointmentFromActiveHold() {
        UUID holdId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Slot slot = new Slot();
        slot.setId(UUID.randomUUID());
        slot.setStatus(Slot.Status.HELD);
        Hold hold = new Hold();
        hold.setId(holdId);
        hold.setSlotId(slot.getId());
        hold.setHoldById(UUID.randomUUID());
        hold.setStatus(Hold.Status.ACTIVE);
        hold.setExpiresAt(NOW.plusNanos(1));
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(hold));
        when(slotRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(i -> i.getArgument(0));

        Appointment appointment = bookingService.confirmBooking(holdId, hold.getHoldById(), actorId);

        assertEquals(slot.getId(), appointment.getBookedSlotId());
        assertEquals(hold.getHoldById(), appointment.getPatientId());
        assertEquals(actorId, appointment.getBookedById());
        assertEquals(Appointment.Status.CONFIRMED, appointment.getStatus());
        assertEquals(Hold.Status.CONFIRMED, hold.getStatus());
        assertEquals(Slot.Status.BOOKED, slot.getStatus());
        verify(holdRepository).save(hold);
        verify(slotRepository).save(slot);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void confirmBooking_ExpiredHoldCreatesNoAppointment(long offset) {
        UUID holdId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Hold hold = new Hold();
        hold.setHoldById(UUID.randomUUID());
        hold.setStatus(Hold.Status.ACTIVE);
        hold.setExpiresAt(NOW.plusNanos(offset));
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(hold));

        assertThrows(SlotUnavailableException.class, () -> bookingService.confirmBooking(holdId, hold.getHoldById(), actorId));
        verifyNoInteractions(slotRepository, appointmentRepository);
        verify(holdRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void holdSlot_StartedSlotIsRejected(long offset) {
        UUID slotId = UUID.randomUUID();
        Slot slot = new Slot();
        slot.setStatus(Slot.Status.OPEN);
        slot.setStartTime(NOW.plusNanos(offset));
        when(slotRepository.findById(slotId)).thenReturn(Optional.of(slot));
        assertThrows(SlotUnavailableException.class,
                () -> bookingService.holdSlot(slotId, UUID.randomUUID()));
        assertEquals(Slot.Status.OPEN, slot.getStatus());
        verify(slotRepository, never()).save(any());
        verifyNoInteractions(holdRepository, appointmentRepository);
    }
}
