package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;
import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, UUID> {
    // Both soft-delete restrictions apply; staff membership alone grants no patient capability.
    @Query("select p from Patient p join fetch p.appUser u where u.id = :accountId and u.enabled = true")
    Optional<Patient> findForEnabledAccount(@Param("accountId") UUID accountId);
}
