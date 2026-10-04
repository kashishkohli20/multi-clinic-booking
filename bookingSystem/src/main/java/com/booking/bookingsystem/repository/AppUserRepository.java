package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.AppUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    // Match the case-insensitive unique index, including older mixed-case records.
    // Hibernate also excludes soft-deleted accounts.
    @Query("select u from AppUser u where lower(u.email) = :email")
    Optional<AppUser> findByNormalizedEmail(@Param("email") String email);

    /** Serializes profile creation with account disabling/deletion. Requires a transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id and u.enabled = true")
    Optional<AppUser> lockEnabledById(@Param("id") UUID id);
}
