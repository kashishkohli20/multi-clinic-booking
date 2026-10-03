package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    // Match the case-insensitive unique index, including older mixed-case records.
    // Hibernate also excludes soft-deleted accounts.
    @Query("select u from AppUser u where lower(u.email) = :email")
    Optional<AppUser> findByNormalizedEmail(@Param("email") String email);
}
