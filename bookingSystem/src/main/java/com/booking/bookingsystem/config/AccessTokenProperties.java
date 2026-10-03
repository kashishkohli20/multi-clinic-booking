package com.booking.bookingsystem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

import java.time.Duration;

@ConfigurationProperties("booking.auth.jwt")
public record AccessTokenProperties(String issuer, String audience, Resource privateKey,
                                    Resource publicKey, @DefaultValue("15m") Duration lifetime) {
    public AccessTokenProperties {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                || privateKey == null || publicKey == null) {
            throw new IllegalArgumentException("JWT issuer, audience, private-key and public-key are required");
        }
        if (lifetime == null || lifetime.isNegative() || lifetime.isZero()
                || lifetime.getNano() != 0 || lifetime.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("JWT lifetime must be whole seconds between 1 second and 15 minutes");
        }
    }
}
