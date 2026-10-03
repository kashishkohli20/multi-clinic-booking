package com.booking.bookingsystem;

import com.booking.bookingsystem.entity.Doctor;
import com.booking.bookingsystem.entity.Patient;
import com.booking.bookingsystem.repository.DoctorRepository;
import com.booking.bookingsystem.repository.PatientRepository;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.booking.bookingsystem.entity.*;
import com.booking.bookingsystem.repository.*;
import com.booking.bookingsystem.service.BookingService;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;

@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
class BookingSystemApplicationTests {
    @Autowired DoctorRepository doctors;
    @Autowired PatientRepository patientsRepository;


    @Autowired BookingService bookingService;
    @Autowired ClinicRepository clinics;
    @Autowired SlotRepository slots;
    @Autowired HoldRepository holds;
    @Autowired AppointmentRepository appointments;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional
    void migratedDatabaseSupportsHoldAndConfirmation() {
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class));
        Clinic clinic = clinics.saveAndFlush(new Clinic());
        Slot slot = new Slot();
        slot.setClinic(clinic);
        slot.setDoctorId(doctors.saveAndFlush(new Doctor()).getId());
        slot.setTimezone("Australia/Melbourne");
        slot.setStartTime(Instant.now().plusSeconds(3600));
        slot.setEndTime(Instant.now().plusSeconds(7200));
        slot.setStatus(Slot.Status.OPEN);
        slot = slots.saveAndFlush(slot);
        UUID patientId = patientsRepository.saveAndFlush(new Patient()).getId();

        Hold hold = bookingService.holdSlot(slot.getId(), patientId);
        holds.flush();
        Appointment appointment = bookingService.confirmBooking(hold.getId(), hold.getHoldById(), null);
        appointments.flush();

        assertEquals(patientId, appointment.getPatientId());
        assertEquals(slot.getId(), appointment.getBookedSlotId());
        assertEquals(Slot.Status.BOOKED, slots.findById(slot.getId()).orElseThrow().getStatus());
        assertEquals(Hold.Status.CONFIRMED, holds.findById(hold.getId()).orElseThrow().getStatus());
    }

}
