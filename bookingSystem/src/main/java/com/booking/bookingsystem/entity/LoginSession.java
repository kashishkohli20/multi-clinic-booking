package com.booking.bookingsystem.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "login_session")
@Getter
@Setter
public class LoginSession {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Hibernate requires eager loading for this reference to a soft-deletable account.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser appUser;

    // Supplied by the lifecycle service using its Clock, rather than entity callbacks.
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastActivityAt;

    // Absolute deadline established at login; activity must never extend it.
    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    @Version
    @Column(nullable = false)
    private Long version;
}
