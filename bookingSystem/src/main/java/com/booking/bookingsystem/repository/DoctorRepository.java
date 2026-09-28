package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DoctorRepository extends JpaRepository<Doctor, UUID> {

    // Spring generates: SELECT * FROM doctor WHERE LOWER(name) LIKE LOWER(CONCAT('%', ?, '%'))
    Optional<Doctor> findByNameContainingIgnoreCase(String name);
}
