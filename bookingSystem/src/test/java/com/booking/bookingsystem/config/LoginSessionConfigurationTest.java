package com.booking.bookingsystem.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class LoginSessionConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LoginSessionConfiguration.class);

    @Test
    void defaultsToEightHours() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(Duration.ofHours(8), context.getBean(LoginSessionProperties.class).maxLifetime());
            assertEquals(Duration.ofMinutes(30), context.getBean(LoginSessionProperties.class).idleTimeout());
        });
    }

    @Test
    void acceptsConfiguredLifetime() {
        contextRunner.withPropertyValues("booking.auth.session.max-lifetime=90m",
                "booking.auth.session.idle-timeout=10m").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(Duration.ofMinutes(90), context.getBean(LoginSessionProperties.class).maxLifetime());
            assertEquals(Duration.ofMinutes(10), context.getBean(LoginSessionProperties.class).idleTimeout());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "not-a-duration", "PT0.000000001S"})
    void invalidLifetimePreventsStartup(String value) {
        contextRunner.withPropertyValues("booking.auth.session.max-lifetime=" + value)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "not-a-duration", "PT0.000000001S"})
    void invalidIdleTimeoutPreventsStartup(String value) {
        contextRunner.withPropertyValues("booking.auth.session.idle-timeout=" + value)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
