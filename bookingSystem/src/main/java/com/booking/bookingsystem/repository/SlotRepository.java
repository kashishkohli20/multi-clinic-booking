package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.Slot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

public interface SlotRepository extends JpaRepository<Slot, UUID> {
    // Spring generates: SELECT * FROM slot WHERE id IN (...)
    List<Slot> findByIdIn(List<UUID> ids);

    // Finds all OPEN slots belonging to a specific doctor
    List<Slot> findByDoctorIdInAndStatusAndStartTimeAfterOrderByStartTimeAscIdAsc(
            List<UUID> doctorIds, Slot.Status status, Instant now);
}
