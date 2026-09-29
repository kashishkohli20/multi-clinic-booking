package com.booking.bookingsystem.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SoftDelete;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "appointment")
@SoftDelete
@Getter
@Setter
public class Appointment {
    public enum Status {CONFIRMED, CANCELLED};

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "patient_id")
    private UUID patientId;

    @Column(name = "booked_by_id")
    private UUID bookedById;

    @Column(name = "booked_slot_id")
    private UUID bookedSlotId;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "canceled_by_id")
    private UUID canceledBy;

    private String canceledReason;

    private Instant canceledAt;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
