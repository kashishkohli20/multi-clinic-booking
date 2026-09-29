package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.Appointment;
import com.booking.bookingsystem.entity.Clinic;
import com.booking.bookingsystem.entity.Slot;
import com.booking.bookingsystem.repository.AppointmentRepository;
import com.booking.bookingsystem.repository.ClinicRepository;
import com.booking.bookingsystem.repository.SlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.booking.bookingsystem.service.exception.SlotUnavailableException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BookingServiceTest {
    @Mock
    private SlotRepository slotRepository;
    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private ClinicRepository clinicRepository;

    @InjectMocks
    private BookingService bookingService;

    private UUID slotId;
    private UUID clinicId;
    private UUID patientId;
    private Slot mockSlot;
    private Clinic mockClinic;

    @BeforeEach
    public void setup() {
        slotId = UUID.randomUUID();
        clinicId = UUID.randomUUID();
        patientId = UUID.randomUUID();

        mockSlot = new Slot();
        mockClinic = new Clinic();

        mockClinic.setId(clinicId);
        mockSlot.setId(slotId);
        mockSlot.setClinic(mockClinic);
        mockSlot.setStatus(Slot.Status.OPEN);
    }

    @Test
    @DisplayName("Should successfully book an appointment when slot is OPEN")
    void bookSlot_Success() {

        when(clinicRepository.findById(clinicId)).thenReturn(Optional.of(mockClinic));

        when(slotRepository.findById(slotId)).thenReturn(Optional.of(mockSlot));
        when(slotRepository.save(mockSlot)).thenReturn(mockSlot);

        when(appointmentRepository.save(any(Appointment.class))).thenAnswer(invocation -> {
            Appointment appt = invocation.getArgument(0);
            appt.setId(UUID.randomUUID());
            return appt;
        });

        Appointment appointment = bookingService.bookSlot(slotId, UUID.fromString(clinicId.toString()), patientId);

        assertNotNull(appointment);
        assertEquals(slotId, appointment.getBookedSlotId());
        assertEquals(Slot.Status.BOOKED, mockSlot.getStatus());
        assertEquals(Appointment.Status.CONFIRMED, appointment.getStatus());
        assertEquals(patientId, appointment.getPatientId());

        verify(slotRepository, times(1)).save(mockSlot);
        verify(appointmentRepository, times(1)).save(any(Appointment.class));

    }

    @Test
    void bookSlot_ClinicMismatch_DoesNotChangeSlotOrSaveAppointment() {
        UUID otherClinicId = UUID.randomUUID();
        Clinic otherClinic = new Clinic();
        otherClinic.setId(otherClinicId);
        when(slotRepository.findById(slotId)).thenReturn(Optional.of(mockSlot));
        when(clinicRepository.findById(otherClinicId)).thenReturn(Optional.of(otherClinic));

        assertThrows(SlotUnavailableException.class,
                () -> bookingService.bookSlot(slotId, otherClinicId, patientId));

        assertEquals(Slot.Status.OPEN, mockSlot.getStatus());
        verify(slotRepository, never()).save(any(Slot.class));
        verifyNoInteractions(appointmentRepository);
    }
}
