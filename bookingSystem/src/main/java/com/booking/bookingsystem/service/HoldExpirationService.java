package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.Hold;
import com.booking.bookingsystem.entity.Slot;
import com.booking.bookingsystem.repository.HoldRepository;
import com.booking.bookingsystem.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HoldExpirationService {
    private final HoldRepository holdRepository;
    private final SlotRepository slotRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireHold(UUID holdId) {
        Hold hold = holdRepository.findById(holdId).orElse(null);
        if (hold == null || hold.getStatus() != Hold.Status.ACTIVE
                || hold.getExpiresAt().isAfter(clock.instant())) {
            return;
        }
        hold.setStatus(Hold.Status.EXPIRED);
        // Check the hold version before reading the slot; this also serializes competing
        // transitions of this hold. A later failure still rolls back the entire transaction.
        holdRepository.saveAndFlush(hold);
        Slot slot = slotRepository.findById(hold.getSlotId())
                .orElseThrow(() -> new IllegalStateException("Missing slot for hold " + holdId));
        if (slot.getStatus() != Slot.Status.HELD) {
            throw new IllegalStateException("Expected HELD slot for active hold " + holdId);
        }
        slot.setStatus(Slot.Status.OPEN);
        // Managed entities are flushed together at commit; version conflicts roll back both.
    }
}
