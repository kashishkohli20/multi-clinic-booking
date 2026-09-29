package com.booking.bookingsystem.service;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.*;
import com.booking.bookingsystem.repository.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@Transactional
class BookingConstraintsIT {
    @Autowired ClinicRepository clinics;
    @Autowired DoctorRepository doctors;
    @Autowired PatientRepository patients;
    @Autowired SlotRepository slots;
    @Autowired AppointmentRepository appointments;
    @Autowired BookingService booking;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {
        "slot set doctor_id = '00000000-0000-0000-0000-000000000000'",
        "hold set slot_id = '00000000-0000-0000-0000-000000000000'",
        "hold set hold_by_id = '00000000-0000-0000-0000-000000000000'",
        "appointment set booked_slot_id = '00000000-0000-0000-0000-000000000000'",
        "appointment set patient_id = '00000000-0000-0000-0000-000000000000'",
        "slot set end_time = start_time",
        "slot set end_time = start_time - interval '1 second'",
        "slot set doctor_id = null", "slot set clinic_id = null",
        "slot set start_time = null", "slot set end_time = null", "slot set timezone = null",
        "slot set status = null", "slot set version = null", "slot set created_at = null", "slot set updated_at = null",
        "hold set slot_id = null", "hold set hold_by_id = null", "hold set expires_at = null",
        "hold set status = null", "hold set version = null", "hold set created_at = null", "hold set updated_at = null",
        "appointment set patient_id = null", "appointment set booked_by_id = null", "appointment set booked_slot_id = null",
        "appointment set status = null", "appointment set created_at = null", "appointment set updated_at = null",
        "slot set status = 'INVALID'", "hold set status = 'INVALID'", "appointment set status = 'INVALID'"
    })
    void rejectsInvalidDatabaseWrites(String mutation) {
        Slot slot = new Slot();
        slot.setClinic(clinics.saveAndFlush(new Clinic()));
        slot.setDoctorId(doctors.saveAndFlush(new Doctor()).getId());
        slot.setStatus(Slot.Status.OPEN);
        slot.setTimezone("UTC");
        slot.setStartTime(Instant.now().plusSeconds(3600));
        slot.setEndTime(Instant.now().plusSeconds(7200));
        slot = slots.saveAndFlush(slot);
        Patient patient = patients.saveAndFlush(new Patient());
        Hold hold = booking.holdSlot(slot.getId(), patient.getId());
        booking.confirmBooking(hold.getId());
        appointments.flush();
        String table = mutation.substring(0, mutation.indexOf(' '));
        Object id = switch (table) {
            case "slot" -> slot.getId();
            case "hold" -> hold.getId();
            default -> appointments.findAll().stream().filter(a -> a.getPatientId().equals(patient.getId()))
                    .findFirst().orElseThrow().getId();
        };
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update " + mutation + " where id = ?", id));
    }
}
