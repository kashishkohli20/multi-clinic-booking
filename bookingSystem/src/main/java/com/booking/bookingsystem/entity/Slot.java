package com.booking.bookingsystem.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SoftDelete;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "slot")
@SoftDelete
@Getter
@Setter
public class Slot {
    public enum Status {
        OPEN,
        HELD,
        BOOKED,
        CANCELED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "doctor_id")
    private UUID doctorId;

    @ManyToOne
    @JoinColumn(name = "clinic_id")
    private Clinic clinic;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    private String timezone;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Version
    private Long version;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public ZonedDateTime getLocalizedStartTime() {
        return this.startTime.atZone(ZoneId.of(this.timezone));
    }

    public ZonedDateTime getLocalizedEndTime() {
        return this.endTime.atZone(ZoneId.of(this.timezone));
    }
}
