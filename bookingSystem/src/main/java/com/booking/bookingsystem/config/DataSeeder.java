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
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Clock;
import com.booking.bookingsystem.service.BookingService;
import java.time.ZoneId;

@Configuration
@Profile("dev")
@ConditionalOnProperty(name = "booking.seed-data", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final Clock clock;
    private final BookingService bookingService;
    private final DoctorRepository doctorRepository;
    private final SlotRepository slotRepository;
    private final PatientRepository patientRepository;
    private final ClinicRepository clinicRepository;

    @Override
    @Transactional
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

        LocalDate tomorrow = LocalDate.now(clock.withZone(ZoneId.of("Australia/Sydney"))).plusDays(1);

        // 3. Create and persist an OPEN Slot for this Doctor (Scheduled for tomorrow at 10:00 AM)
        Slot openSlot = new Slot();
        openSlot.setDoctorId(doctor.getId()); // Perfectly maps the newly generated UUID!
        openSlot.setStatus(Slot.Status.OPEN);
        openSlot.setClinic(southbankClinic);
        openSlot.setTimezone("Australia/Sydney");
        ZoneId slotZone = ZoneId.of(openSlot.getTimezone());

        openSlot.setStartTime(
                tomorrow.atTime(10, 0)
                        .atZone(slotZone)
                        .toInstant()
        );
        openSlot.setEndTime(
                tomorrow.atTime(11, 0)
                .atZone(slotZone)
                .toInstant());
        slotRepository.save(openSlot);
        log.info("Seeded OPEN Slot for tomorrow at 10:00 AM for Doctor ID: {}", doctor.getId());

        // 4. Create and persist an already BOOKED Slot to test filtering rules
        Slot bookedSlot = new Slot();
        bookedSlot.setDoctorId(doctor.getId());
        bookedSlot.setStatus(Slot.Status.OPEN);
        bookedSlot.setClinic(docklandsClinic);
        bookedSlot.setTimezone("Australia/Sydney");
        ZoneId bookedSlotZone = ZoneId.of(bookedSlot.getTimezone());

        bookedSlot.setStartTime(
                tomorrow.atTime(14, 0)
                        .atZone(bookedSlotZone)
                        .toInstant());
        bookedSlot.setEndTime(
                tomorrow.atTime(15, 0)
                        .atZone(bookedSlotZone)
                        .toInstant());
        bookedSlot = slotRepository.save(bookedSlot);
        var hold = bookingService.holdSlot(bookedSlot.getId(), patient.getId());
        bookingService.confirmBooking(hold.getId());
        log.info("Seeded BOOKED Slot for tomorrow at 2:00 PM (Should be filtered out by search queries)");

        log.info("Data seeding completed successfully!");


    }
}
