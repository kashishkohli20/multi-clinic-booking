package com.booking.bookingsystem.service;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.*;
import com.booking.bookingsystem.repository.*;
import com.booking.bookingsystem.config.ReservationConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.dao.OptimisticLockingFailureException;
import com.booking.bookingsystem.service.exception.SlotUnavailableException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
class ReservationDeadlineIT {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    @Autowired HoldRepository holds;
    @Autowired SlotRepository slots;
    @Autowired ClinicRepository clinics;
    @Autowired AppointmentRepository appointments;
    @Autowired HoldExpirationService expiry;
    @Autowired BookingService booking;
    @MockitoBean Clock clock;

    @BeforeEach
    void setup() { when(clock.instant()).thenReturn(NOW); }

    @AfterEach
    void cleanup() {
        appointments.deleteAll();
        holds.deleteAll();
        slots.deleteAll();
        clinics.deleteAll();
    }

    private Hold fixture(Instant deadline, Slot.Status slotStatus, Hold.Status status) {
        Clinic clinic = clinics.saveAndFlush(new Clinic());
        Slot slot = new Slot();
        slot.setClinic(clinic);
        slot.setStatus(slotStatus);
        slot.setStartTime(NOW.plusSeconds(3600));
        slot.setEndTime(NOW.plusSeconds(7200));
        slot = slots.saveAndFlush(slot);
        Hold hold = new Hold();
        hold.setSlotId(slot.getId());
        hold.setHoldById(UUID.randomUUID());
        hold.setStatus(status);
        hold.setExpiresAt(deadline);
        return holds.saveAndFlush(hold);
    }

    private void assertState(Hold hold, Hold.Status status, Slot.Status slotStatus) {
        assertEquals(status, holds.findById(hold.getId()).orElseThrow().getStatus());
        assertEquals(slotStatus, slots.findById(hold.getSlotId()).orElseThrow().getStatus());
    }

    @Test
    void cleanupExpiresBeforeAndAtDeadlineButLeavesFutureAndConfirmedHolds() {
        Hold before = fixture(NOW.minusSeconds(1), Slot.Status.HELD, Hold.Status.ACTIVE);
        Hold at = fixture(NOW, Slot.Status.HELD, Hold.Status.ACTIVE);
        Hold future = fixture(NOW.plusSeconds(1), Slot.Status.HELD, Hold.Status.ACTIVE);
        Hold confirmed = fixture(NOW, Slot.Status.BOOKED, Hold.Status.CONFIRMED);
        new ExpirationCleanupWorker(holds, expiry, clock).cleanupExpiredHolds();
        expiry.expireHold(future.getId());
        expiry.expireHold(confirmed.getId());
        expiry.expireHold(UUID.randomUUID());
        assertState(before, Hold.Status.EXPIRED, Slot.Status.OPEN);
        assertState(at, Hold.Status.EXPIRED, Slot.Status.OPEN);
        assertState(future, Hold.Status.ACTIVE, Slot.Status.HELD);
        assertState(confirmed, Hold.Status.CONFIRMED, Slot.Status.BOOKED);
    }

    @Test
    void inconsistentHoldDoesNotPreventOtherHoldsFromExpiring() {
        Hold broken = fixture(NOW.minusSeconds(1), Slot.Status.BOOKED, Hold.Status.ACTIVE);
        Hold healthy = fixture(NOW, Slot.Status.HELD, Hold.Status.ACTIVE);
        new ExpirationCleanupWorker(holds, expiry, clock).cleanupExpiredHolds();
        assertState(broken, Hold.Status.ACTIVE, Slot.Status.BOOKED);
        assertState(healthy, Hold.Status.EXPIRED, Slot.Status.OPEN);
    }

    // Both operations reach the clock after reading the same ACTIVE hold version.
    private void race(Hold hold, boolean confirm) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ThreadLocal<Instant> decisionTime = new ThreadLocal<>();
        when(clock.instant()).thenAnswer(invocation -> {
            barrier.await(10, TimeUnit.SECONDS);
            return decisionTime.get();
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> {
                decisionTime.set(confirm ? NOW.minusNanos(1) : NOW);
                try {
                    if (confirm) booking.confirmBooking(hold.getId());
                    else expiry.expireHold(hold.getId());
                } catch (OptimisticLockingFailureException | SlotUnavailableException expected) {
                    // The competing transaction won.
                }
            });
            Future<?> second = pool.submit(() -> {
                decisionTime.set(NOW);
                try { expiry.expireHold(hold.getId()); }
                catch (OptimisticLockingFailureException expected) { }
            });
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoWorkersExpireSameHoldSafely() throws Exception {
        Hold hold = fixture(NOW, Slot.Status.HELD, Hold.Status.ACTIVE);
        race(hold, false);
        assertState(hold, Hold.Status.EXPIRED, Slot.Status.OPEN);
    }

    @Test
    void confirmationRacingExpiryLeavesConsistentBooking() throws Exception {
        Hold hold = fixture(NOW, Slot.Status.HELD, Hold.Status.ACTIVE);
        race(hold, true);
        Hold.Status status = holds.findById(hold.getId()).orElseThrow().getStatus();
        long count = appointments.findAll().stream()
                .filter(a -> hold.getSlotId().equals(a.getBookedSlotId())).count();
        if (status == Hold.Status.CONFIRMED) {
            assertState(hold, status, Slot.Status.BOOKED);
            assertEquals(1, count);
        } else {
            assertState(hold, Hold.Status.EXPIRED, Slot.Status.OPEN);
            assertEquals(0, count);
        }
    }

    @Test
    void holdDurationMustBePositive() {
        var configuration = new ReservationConfiguration();
        assertThrows(IllegalArgumentException.class, () -> configuration.holdDuration(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> configuration.holdDuration(Duration.ofSeconds(-1)));
    }
}
