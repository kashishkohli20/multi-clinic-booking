package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DoctorRepository extends JpaRepository<Doctor, UUID> {

    // Spring generates: SELECT * FROM doctor WHERE LOWER(name) LIKE LOWER(CONCAT('%', ?, '%'))
    List<Doctor> findByNameContainingIgnoreCase(String name);

    // Explicit join applies soft-delete restrictions to both profile and account.
    @Query("select d from Doctor d join fetch d.appUser u where u.id = :accountId and u.enabled = true")
    Optional<Doctor> findForEnabledAccount(@Param("accountId") UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Doctor d where d.appUser.id = :accountId")
    Optional<Doctor> lockForAccount(@Param("accountId") UUID accountId);
}
