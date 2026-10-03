package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.LoginSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

public interface LoginSessionRepository extends JpaRepository<LoginSession, UUID> {
    // The explicit account join also applies Hibernate's soft-delete restriction.
    @Query("""
            select s from LoginSession s
            join fetch s.appUser u
            where s.id = :sessionId and u.id = :accountId and u.enabled = true
            """)
    Optional<LoginSession> findForEnabledAccount(@Param("sessionId") UUID sessionId,
                                               @Param("accountId") UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from LoginSession s
            join fetch s.appUser u
            where s.id = :sessionId and u.id = :accountId and u.enabled = true
            """)
    Optional<LoginSession> lockForEnabledAccount(@Param("sessionId") UUID sessionId,
                                               @Param("accountId") UUID accountId);

    // Native SQL deliberately permits revocation after account deletion or disabling.
    // Increment the version so a previously loaded entity cannot overwrite revocation.
    // Flush before clearing so joined callers do not lose pending writes, and cannot
    // subsequently read a cached session that still appears unrevoked.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update login_session
            set revoked_at = greatest(:now, created_at), version = version + 1
            where id = :sessionId and user_id = :accountId and revoked_at is null
            """, nativeQuery = true)
    int revokeIfNotRevoked(@Param("sessionId") UUID sessionId,
                          @Param("accountId") UUID accountId,
                          @Param("now") Instant now);
}
