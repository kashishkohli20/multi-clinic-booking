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
@Table(name = "hold")
@SoftDelete
@Getter
@Setter
public class Hold {
    public enum Status {
        ACTIVE,
        CONFIRMED,
        EXPIRED,
        CANCELED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "slot_id")
    private UUID slotId;

    @Column(name = "hold_by_id")
    private UUID holdById;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Version
    private Long version;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
