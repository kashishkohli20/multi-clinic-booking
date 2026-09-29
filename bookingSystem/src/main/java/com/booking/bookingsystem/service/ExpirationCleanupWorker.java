package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.Hold;
import com.booking.bookingsystem.repository.HoldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "booking.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class ExpirationCleanupWorker {
    private final HoldRepository holdRepository;
    private final HoldExpirationService holdExpirationService;
    private final Clock clock;

    @Scheduled(fixedDelay = 10000)
    public void cleanupExpiredHolds() {
        var candidates = holdRepository.findExpiredCandidateIds(
                Hold.Status.ACTIVE, clock.instant(), PageRequest.of(0, 100));
        for (UUID holdId : candidates) {
            try {
                holdExpirationService.expireHold(holdId);
            } catch (OptimisticLockingFailureException ex) {
                log.debug("Concurrent change prevented expiry of hold {}", holdId);
            } catch (RuntimeException ex) {
                log.error("Failed to expire hold {}", holdId, ex);
            }
        }
    }
}
