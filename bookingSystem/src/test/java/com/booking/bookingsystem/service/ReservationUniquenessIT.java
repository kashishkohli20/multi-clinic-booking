package com.booking.bookingsystem.service;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.*;
import com.booking.bookingsystem.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@Transactional
class ReservationUniquenessIT {
    @Autowired ClinicRepository clinics;
    @Autowired DoctorRepository doctors;
    @Autowired PatientRepository patients;
    @Autowired SlotRepository slots;
    @Autowired JdbcTemplate jdbc;
    private UUID slotId;
    private UUID patientId;

    @BeforeEach
    void fixtures() {
        Slot slot = new Slot();
        slot.setClinic(clinics.saveAndFlush(new Clinic()));
        slot.setDoctorId(doctors.saveAndFlush(new Doctor()).getId());
        slot.setStatus(Slot.Status.OPEN);
        slot.setTimezone("UTC");
        slot.setStartTime(Instant.now().plusSeconds(3600));
        slot.setEndTime(Instant.now().plusSeconds(7200));
        slotId = slots.saveAndFlush(slot).getId();
        patientId = patients.saveAndFlush(new Patient()).getId();
    }

    private void insert(String table, String status, boolean deleted) {
        if (table.equals("hold")) {
            jdbc.update("insert into hold (id,slot_id,hold_by_id,status,expires_at,version,created_at,updated_at,deleted) "
                    + "values (?,?,?,?,now()+interval '1 minute',0,now(),now(),?)",
                    UUID.randomUUID(), slotId, patientId, status, deleted);
        } else {
            jdbc.update("insert into appointment (id,booked_slot_id,patient_id,booked_by_id,status,created_at,updated_at,deleted) "
                    + "values (?,?,?,?,?,now(),now(),?)",
                    UUID.randomUUID(), slotId, patientId, null, status, deleted);
        }
    }

    @ParameterizedTest
    @CsvSource({"hold,ACTIVE,false", "hold,ACTIVE,true", "appointment,CONFIRMED,false", "appointment,CONFIRMED,true"})
    void duplicateLiveReservationRejectedEvenWhenOriginalSoftDeleted(String table, String status, boolean deleted) {
        insert(table, status, deleted);
        assertThrows(DataIntegrityViolationException.class, () -> insert(table, status, false));
    }

    @ParameterizedTest
    @CsvSource({"hold,EXPIRED,ACTIVE", "hold,CANCELED,ACTIVE", "hold,CONFIRMED,ACTIVE", "appointment,CANCELLED,CONFIRMED"})
    void historicalRowsDoNotBlockNewReservation(String table, String historical, String live) {
        insert(table, historical, false);
        insert(table, historical, true);
        insert(table, live, false);
        assertEquals(3, jdbc.queryForObject("select count(*) from " + table + " where "
                + (table.equals("hold") ? "slot_id" : "booked_slot_id") + " = ?", Integer.class, slotId));
    }

    @ParameterizedTest
    @CsvSource({"hold,ACTIVE,EXPIRED", "appointment,CONFIRMED,CANCELLED"})
    void lifecycleTransitionReleasesUniqueness(String table, String live, String finished) {
        insert(table, live, false);
        String column = table.equals("hold") ? "slot_id" : "booked_slot_id";
        jdbc.update("update " + table + " set status = ? where " + column + " = ?", finished, slotId);
        insert(table, live, false);
    }
}
