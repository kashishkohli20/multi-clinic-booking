package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    // Deliberately includes consumed/expired credentials and revoked sessions.
    // Finding a row is not authorization to refresh; the lifecycle service must check eligibility.
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
