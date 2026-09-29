package com.booking.bookingsystem.service;

import com.booking.bookingsystem.PostgresTestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.booking.bookingsystem.entity.Clinic;
import com.booking.bookingsystem.entity.Hold;
import com.booking.bookingsystem.repository.HoldRepository;
import com.booking.bookingsystem.repository.AppointmentRepository;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.booking.bookingsystem.entity.Slot;
import com.booking.bookingsystem.repository.SlotRepository;
import com.booking.bookingsystem.repository.ClinicRepository;
import com.booking.bookingsystem.service.exception.SlotUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest // Boots the real Spring application context and database for an Integration Test
class BookingConcurrencyIT {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private ClinicRepository clinicRepository;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Test
    @DisplayName("Should only allow one user to hold a slot when multiple requests land simultaneously")
    void verifyOptimisticLocking_UnderConcurrentLoad() throws Exception {
        // 1. Arrange: Create and save an open slot into the database
        Slot slot = new Slot();
        slot.setStatus(Slot.Status.OPEN);
        slot.setStartTime(Instant.now().plus(1, ChronoUnit.DAYS));
        slot.setEndTime(Instant.now().plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
        slot.setDoctorId(UUID.randomUUID());
        Clinic clinic = new Clinic();
        clinic.setName("Concurrency test clinic");
        clinic = clinicRepository.saveAndFlush(clinic);
        slot.setClinic(clinic);
        slot = slotRepository.saveAndFlush(slot);

        UUID targetSlotId = slot.getId();

        // 2. Setup Multi-threading tools
        int threadCount = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1); // Synchronizes the start time of all threads

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // 3. Act: Submit 5 booking tasks to run concurrently
        var tasks = new ArrayList<Future<?>>();
        for (int i = 0; i < threadCount; i++) {
            UUID patientId = UUID.randomUUID(); // Give each concurrent thread its own fake patient

            tasks.add(executorService.submit(() -> {
                try {
                    latch.await(); // Wait here until the gate opens to ensure they all strike at once
                    bookingService.holdSlot(targetSlotId, patientId);
                    successCount.incrementAndGet();
                } catch (SlotUnavailableException | org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                    failureCount.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        latch.countDown(); // Open the gate! All threads execute holdSlot() simultaneously
        executorService.shutdown();

        // Wait for all threads to finish processing
        try {
            for (Future<?> task : tasks) {
                task.get(15, TimeUnit.SECONDS);
            }
        } finally {
            executorService.shutdownNow();
        }

        // 4. Assertions: Out of 5 concurrent requests, exactly 1 must succeed, and 4 must fail
        assertEquals(1, successCount.get(), "Exactly one hold should succeed.");
        assertEquals(4, failureCount.get(), "Four threads should have been rejected by locking rules.");

        // Check database state to ensure it didn't get corrupted
        Slot updatedSlot = slotRepository.findById(targetSlotId).orElseThrow();
        assertEquals(Slot.Status.HELD, updatedSlot.getStatus());
        assertEquals(1, holdRepository.findAll().stream()
                .filter(h -> targetSlotId.equals(h.getSlotId()) && h.getStatus() == Hold.Status.ACTIVE).count());
        assertEquals(0, appointmentRepository.findAll().stream()
                .filter(a -> targetSlotId.equals(a.getBookedSlotId())).count());
    }
}