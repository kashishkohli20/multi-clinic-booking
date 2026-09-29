package com.booking.bookingsystem.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReservationConfiguration {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public Duration holdDuration(@Value("${booking.hold-duration:30s}") Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("booking.hold-duration must be positive");
        }
        return duration;
    }
}
