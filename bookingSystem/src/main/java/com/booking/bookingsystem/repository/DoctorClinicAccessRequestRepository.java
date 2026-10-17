package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.DoctorClinicAccessRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.Optional;

public interface DoctorClinicAccessRequestRepository extends JpaRepository<DoctorClinicAccessRequest, UUID> {
    // Persistence lookup only: neither account eligibility nor clinic authorization is implied.
    boolean existsByDoctorIdAndClinicIdAndStatus(UUID doctorId, UUID clinicId, DoctorClinicAccessRequest.Status status);
    Optional<DoctorClinicAccessRequest> findByIdAndDoctorId(UUID id, UUID doctorId);
}
