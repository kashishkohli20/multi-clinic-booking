package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    // Deliberately includes consumed/expired credentials and revoked sessions.
    // Finding a row is not authorization to refresh; the lifecycle service must check eligibility.
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // Locate the lock owner without caching a token entity before waiting on its session lock.
    @Query(value = "select session_id from refresh_token where token_hash = :hash", nativeQuery = true)
    Optional<UUID> findSessionIdByTokenHash(@Param("hash") String hash);

    boolean existsByLoginSession_Id(UUID sessionId);
}
