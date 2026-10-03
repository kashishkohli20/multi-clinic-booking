package com.booking.bookingsystem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("booking.auth.session")
public record LoginSessionProperties(
        @DefaultValue("8h") Duration maxLifetime,
        @DefaultValue("30m") Duration idleTimeout) {
    public LoginSessionProperties {
        validateDuration("max-lifetime", maxLifetime);
        validateDuration("idle-timeout", idleTimeout);
    }

    private static void validateDuration(String name, Duration duration) {
        // Match PostgreSQL timestamptz(6) precision, including for custom durations.
        if (duration == null || duration.isZero() || duration.isNegative()
                || duration.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "booking.auth.session." + name + " must be positive and use whole microseconds");
        }
    }
}
