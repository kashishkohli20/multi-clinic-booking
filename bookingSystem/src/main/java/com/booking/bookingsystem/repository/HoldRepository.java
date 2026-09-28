package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.Hold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {
    // Spring generates: SELECT * FROM hold WHERE status = ? AND expires_at < ?
    List<Hold> findByStatusAndExpiresAtBefore(Hold.Status status, Instant time);
}
