package com.booking.bookingsystem.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Internal request/decision history, not a membership or an API response. */
@Entity
@Table(name = "doctor_clinic_access_request")
@Getter
@Setter
public class DoctorClinicAccessRequest {
    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // FK-backed IDs preserve readable history when referenced entities are soft-deleted.
    // No cascading operations and no soft deletion of the request itself.
    @Column(nullable = false, updatable = false)
    private UUID doctorId;

    @Column(nullable = false, updatable = false)
    private UUID clinicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.PENDING;

    // The future lifecycle service will supply timestamps from Clock.
    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    private Instant decidedAt;

    // AppUser identity of the administrator making the decision, not a profile ID.
    private UUID decidedById;

    @Version
    @Column(nullable = false)
    private Long version;
}
