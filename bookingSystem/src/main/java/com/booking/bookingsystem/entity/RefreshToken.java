package com.booking.bookingsystem.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Internal credential history. Never expose this entity through an API. */
@Entity
@Table(name = "refresh_token")
@Getter
@Setter
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // No cascade or soft deletion: consumed credentials must remain discoverable for replay detection.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private LoginSession loginSession;

    // Lowercase hexadecimal SHA-256 digest of the future random opaque token, never its raw value.
    @Column(nullable = false, updatable = false, length = 64)
    private String tokenHash;

    // The future lifecycle service supplies timestamps using Clock.
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant consumedAt;

    @Version
    @Column(nullable = false)
    private Long version;
}
