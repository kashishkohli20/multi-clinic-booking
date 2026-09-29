package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.Hold;
import com.booking.bookingsystem.repository.HoldRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;

class ExpirationCleanupWorkerTest {
    @Test
    void conflictDoesNotStopNextCandidate() {
        HoldRepository repository = mock(HoldRepository.class);
        HoldExpirationService expiry = mock(HoldExpirationService.class);
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.findExpiredCandidateIds(Hold.Status.ACTIVE, now, PageRequest.of(0, 100)))
                .thenReturn(List.of(first, second));
        doThrow(new OptimisticLockingFailureException("Concurrent confirmation"))
                .when(expiry).expireHold(first);
        new ExpirationCleanupWorker(repository, expiry, Clock.fixed(now, ZoneOffset.UTC))
                .cleanupExpiredHolds();
        var order = inOrder(expiry);
        order.verify(expiry).expireHold(first);
        order.verify(expiry).expireHold(second);
    }
}
