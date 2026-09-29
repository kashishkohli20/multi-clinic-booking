package com.booking.bookingsystem.config;

import com.booking.bookingsystem.entity.*;
import com.booking.bookingsystem.repository.*;
import com.booking.bookingsystem.service.BookingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataSeederTest {
    @Test
    void sampleBookingUsesReservationFlowAndClinicLocalTime() throws Exception {
        var doctors = mock(DoctorRepository.class);
        var slots = mock(SlotRepository.class);
        var patients = mock(PatientRepository.class);
        var clinics = mock(ClinicRepository.class);
        var booking = mock(BookingService.class);
        when(doctors.save(any())).thenAnswer(i -> { Doctor d = i.getArgument(0); d.setId(UUID.randomUUID()); return d; });
        when(patients.save(any())).thenAnswer(i -> { Patient p = i.getArgument(0); p.setId(UUID.randomUUID()); return p; });
        when(clinics.save(any())).thenAnswer(i -> { Clinic c = i.getArgument(0); c.setId(UUID.randomUUID()); return c; });
        when(slots.save(any())).thenAnswer(i -> { Slot s = i.getArgument(0); s.setId(UUID.randomUUID()); return s; });
        Hold hold = new Hold();
        hold.setId(UUID.randomUUID());
        when(booking.holdSlot(any(), any())).thenReturn(hold);
        new DataSeeder(Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC),
                booking, doctors, slots, patients, clinics).run();
        var capture = ArgumentCaptor.forClass(Slot.class);
        verify(slots, times(2)).save(capture.capture());
        var created = capture.getAllValues();
        assertEquals(Instant.parse("2030-01-01T23:00:00Z"), created.get(0).getStartTime());
        assertEquals(Instant.parse("2030-01-02T03:00:00Z"), created.get(1).getStartTime());
        assertNotEquals(created.get(0).getClinic().getId(), created.get(1).getClinic().getId());
        verify(booking).holdSlot(eq(created.get(1).getId()), any());
        verify(booking).confirmBooking(hold.getId());
    }
}
