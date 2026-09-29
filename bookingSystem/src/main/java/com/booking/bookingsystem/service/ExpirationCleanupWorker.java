package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.Hold;
import com.booking.bookingsystem.entity.Slot;
import com.booking.bookingsystem.repository.HoldRepository;
import com.booking.bookingsystem.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j // Enables production-grade logging via Slf4j
public class ExpirationCleanupWorker {

    private final HoldRepository holdRepository;
    private final SlotRepository slotRepository;
    private final Clock clock;

    /**
     * Wakes up at a fixed interval to sweep and clean expired slot holds.
     * fixedDelay = 10000 means it executes 10 seconds after the previous run completes.
     */
    @Scheduled(fixedDelay = 10000)
    @Transactional // Ensures the entire sweep-and-release operation is atomic
    public void cleanupExpiredHolds() {
        Instant now = clock.instant();

        // 1. Fetch all active holds that have passed their expiration timestamp
        List<Hold> expiredHolds = holdRepository.findByStatusAndExpiresAtLessThanEqual(Hold.Status.ACTIVE, now);

        if (expiredHolds.isEmpty()) {
            return; // Exit early if there is nothing to clean up
        }

        log.info("Found {} expired holds to clean up.", expiredHolds.size());

        // 2. Mark holds as EXPIRED
        expiredHolds.forEach(hold -> hold.setStatus(Hold.Status.EXPIRED));
        holdRepository.saveAll(expiredHolds);

        // 3. Extract the associated Slot IDs from those holds
        List<UUID> slotIdsToRelease = expiredHolds.stream()
                .map(Hold::getSlotId)
                .collect(Collectors.toList());

        // 4. Fetch the corresponding slots and reset their statuses back to OPEN
        List<Slot> slotsToRelease = slotRepository.findByIdIn(slotIdsToRelease);
        for (Slot slot : slotsToRelease) {
            // Only revert if it hasn't accidentally been booked via a race condition
            if (slot.getStatus() == Slot.Status.HELD) {
                slot.setStatus(Slot.Status.OPEN);
            }
        }

        slotRepository.saveAll(slotsToRelease); // Bulk database batch save
        log.info("Successfully released {} slots back to OPEN status.", slotsToRelease.size());
    }
}