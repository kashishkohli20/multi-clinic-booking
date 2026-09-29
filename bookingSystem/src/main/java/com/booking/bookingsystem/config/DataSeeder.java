package com.booking.bookingsystem.config;

import com.booking.bookingsystem.entity.Clinic;
import com.booking.bookingsystem.entity.Doctor;
import com.booking.bookingsystem.entity.Patient;
import com.booking.bookingsystem.entity.Slot;
import com.booking.bookingsystem.repository.ClinicRepository;
import com.booking.bookingsystem.repository.DoctorRepository;
import com.booking.bookingsystem.repository.PatientRepository;
import com.booking.bookingsystem.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final DoctorRepository doctorRepository;
    private final SlotRepository slotRepository;
    private final PatientRepository patientRepository;
    private final ClinicRepository clinicRepository;

    @Override
    public void run(String... args) throws Exception {
        if (doctorRepository.count() > 0) {
            log.info("Database already seeded with doctors. Skipping initialization.");
            return;
        }

        log.info("Initializing test data seeding...");

        // 2. Create and persist a Test Doctor
        Doctor doctor = new Doctor();
        doctor.setName("Dr. Hibbert");
        doctor.setDescription("Chief of General Medicine, expert conversationalist.");
        doctor = doctorRepository.save(doctor);
        log.info("Seeded Doctor: {} with ID: {}", doctor.getName(), doctor.getId());

        Patient patient = new Patient();
        patient.setName("Mr. Kashish");
        patient.setDescription("Best in the world");
        patient = patientRepository.save(patient);
        log.info("Seeded Patient: {} with ID: {}", patient.getName(), patient.getId());


        Clinic southbankClinic = new Clinic();
        southbankClinic.setName("Southbank Medical Clinic");
        southbankClinic.setDescription("Southbank medical clinic for local people");
        southbankClinic = clinicRepository.save(southbankClinic);
        log.info("Seeded Clinic: {} with ID: {}", southbankClinic.getName(), southbankClinic.getId());

        Clinic docklandsClinic = new Clinic();
        docklandsClinic.setName("Docklands Medical Clinic");
        docklandsClinic.setDescription("Docklands medical clinic for local people");
        docklandsClinic = clinicRepository.save(docklandsClinic);
        log.info("Seeded Clinic: {} with ID: {}", docklandsClinic.getName(), docklandsClinic.getId());

        // 3. Create and persist an OPEN Slot for this Doctor (Scheduled for tomorrow at 10:00 AM)
        Slot openSlot = new Slot();
        openSlot.setDoctorId(doctor.getId()); // Perfectly maps the newly generated UUID!
        openSlot.setStatus(Slot.Status.OPEN);
        openSlot.setClinic(southbankClinic);
        openSlot.setTimezone("Australia/Sydney");
        ZoneId slotZone = ZoneId.of(openSlot.getTimezone());

        openSlot.setStartTime(
                LocalDateTime.now(slotZone).plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)
                        .atZone(slotZone)
                        .toInstant()
        );
        openSlot.setEndTime(
                LocalDateTime.now(slotZone).plusDays(1).withHour(11).withMinute(0).withSecond(0).withNano(0)
                .atZone(slotZone)
                .toInstant());
        slotRepository.save(openSlot);
        log.info("Seeded OPEN Slot for tomorrow at 10:00 AM for Doctor ID: {}", doctor.getId());

        // 4. Create and persist an already BOOKED Slot to test filtering rules
        Slot bookedSlot = new Slot();
        bookedSlot.setDoctorId(doctor.getId());
        bookedSlot.setStatus(Slot.Status.BOOKED);
        bookedSlot.setClinic(southbankClinic);
        bookedSlot.setTimezone("Australia/Sydney");
        ZoneId bookedSlotZone = ZoneId.of(bookedSlot.getTimezone());

        bookedSlot.setStartTime(
                LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0)
                        .atZone(bookedSlotZone)
                        .toInstant());
        bookedSlot.setEndTime(
                LocalDateTime.now().plusDays(1).withHour(15).withMinute(0).withSecond(0).withNano(0)
                        .atZone(bookedSlotZone)
                        .toInstant());
        slotRepository.save(bookedSlot);
        log.info("Seeded BOOKED Slot for tomorrow at 2:00 PM (Should be filtered out by search queries)");

        log.info("Data seeding completed successfully!");


    }
}
