package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.Clinic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ClinicRepository extends JpaRepository<Clinic, UUID> {
}
