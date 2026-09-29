package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.*;
import com.booking.bookingsystem.repository.*;
import com.booking.bookingsystem.service.exception.SlotUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
    @Mock private SlotRepository slotRepository;
    @Mock private HoldRepository holdRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @InjectMocks private BookingService bookingService;

    @Test
    void holdSlot_CreatesHoldWithoutAppointment() {
        UUID slotId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        Slot slot = new Slot();
        slot.setId(slotId);
        slot.setStatus(Slot.Status.OPEN);
        when(slotRepository.findById(slotId)).thenReturn(Optional.of(slot));

        Hold hold = bookingService.holdSlot(slotId, patientId);

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
        Slot slot = new Slot();
        slot.setId(UUID.randomUUID());
        slot.setStatus(Slot.Status.HELD);
        Hold hold = new Hold();
        hold.setId(holdId);
        hold.setSlotId(slot.getId());
        hold.setHoldById(UUID.randomUUID());
        hold.setStatus(Hold.Status.ACTIVE);
        hold.setExpiresAt(Instant.now().plusSeconds(300));
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(hold));
        when(slotRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(i -> i.getArgument(0));

        Appointment appointment = bookingService.confirmBooking(holdId);

        assertEquals(slot.getId(), appointment.getBookedSlotId());
        assertEquals(hold.getHoldById(), appointment.getPatientId());
        assertEquals(Appointment.Status.CONFIRMED, appointment.getStatus());
        assertEquals(Hold.Status.CONFIRMED, hold.getStatus());
        assertEquals(Slot.Status.BOOKED, slot.getStatus());
        verify(holdRepository).save(hold);
        verify(slotRepository).save(slot);
    }

    @Test
    void confirmBooking_ExpiredHoldCreatesNoAppointment() {
        UUID holdId = UUID.randomUUID();
        Hold hold = new Hold();
        hold.setStatus(Hold.Status.ACTIVE);
        hold.setExpiresAt(Instant.now().minusSeconds(60));
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(hold));

        assertThrows(SlotUnavailableException.class, () -> bookingService.confirmBooking(holdId));
        verifyNoInteractions(slotRepository, appointmentRepository);
        verify(holdRepository, never()).save(any());
    }
}
