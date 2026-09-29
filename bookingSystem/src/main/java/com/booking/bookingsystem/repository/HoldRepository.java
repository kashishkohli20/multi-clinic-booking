package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.Hold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {
    @Query("select h.id from Hold h where h.status = :status and h.expiresAt <= :now "
            + "order by h.expiresAt, h.id")
    List<UUID> findExpiredCandidateIds(@Param("status") Hold.Status status,
                                       @Param("now") Instant now, Pageable pageable);
}
