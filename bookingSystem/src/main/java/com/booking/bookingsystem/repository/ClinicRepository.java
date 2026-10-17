package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.Clinic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.UUID;

public interface ClinicRepository extends JpaRepository<Clinic, UUID> {
    // Shared lock keeps the clinic eligible while allowing requests from different doctors.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Clinic c where c.id = :id")
    Optional<Clinic> lockById(@Param("id") UUID id);
}
