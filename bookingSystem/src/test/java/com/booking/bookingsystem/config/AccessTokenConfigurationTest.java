package com.booking.bookingsystem.config;

import com.booking.bookingsystem.security.JwtTestKeys;
import com.booking.bookingsystem.service.AccessTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccessTokenConfigurationTest {
    private static final KeyPair KEYS = JwtTestKeys.generate(2048);
    @TempDir Path directory;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AccessTokenConfiguration.class).withBean(Clock.class, Clock::systemUTC);
    private ApplicationContextRunner enabled;

    @BeforeEach
    void setUp() throws Exception {
        writePair(KEYS);
        enabled = runner.withPropertyValues("booking.auth.jwt.enabled=true",
                "booking.auth.jwt.issuer=https://issuer.example.test", "booking.auth.jwt.audience=booking-api",
                "booking.auth.jwt.private-key=" + directory.resolve("private.pem").toUri(),
                "booking.auth.jwt.public-key=" + directory.resolve("public.pem").toUri());
    }

    @Test
    void disabledFoundationRequiresNoKeysAndProvidesNoService() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(AccessTokenService.class).isEmpty());
        });
    }

    @Test
    void loadsExternalKeysAndDefaultsTo15Minutes() {
        enabled.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(Duration.ofMinutes(15), context.getBean(AccessTokenProperties.class).lifetime());
            var service = context.getBean(AccessTokenService.class);
            var account = UUID.randomUUID();
            assertEquals(account, service.verify(service.issue(account, UUID.randomUUID())).accountId());
        });
    }

    @Test
    void allowsShorterWholeSecondLifetime() {
        enabled.withPropertyValues("booking.auth.jwt.lifetime=5m").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(Duration.ofMinutes(5), context.getBean(AccessTokenProperties.class).lifetime());
        });
    }

    @Test
    void enablingWithoutConfigurationFailsStartup() {
        runner.withPropertyValues("booking.auth.jwt.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer=", "audience=", "private-key=file:/missing-private.pem",
            "public-key=file:/missing-public.pem", "lifetime=0s", "lifetime=-1s", "lifetime=16m",
            "lifetime=PT0.5S", "lifetime=invalid"})
    void invalidConfigurationFailsStartup(String property) {
        enabled.withPropertyValues("booking.auth.jwt." + property)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"weak", "mismatched", "malformed-private", "malformed-public"})
    void unusableKeysFailStartup(String scenario) throws Exception {
        switch (scenario) {
            case "weak" -> writePair(JwtTestKeys.generate(1024));
            case "mismatched" -> Files.writeString(directory.resolve("public.pem"),
                    JwtTestKeys.pem("PUBLIC KEY", JwtTestKeys.generate(2048).getPublic().getEncoded()));
            case "malformed-private" -> Files.writeString(directory.resolve("private.pem"), "invalid");
            case "malformed-public" -> Files.writeString(directory.resolve("public.pem"), "invalid");
        }
        enabled.run(context -> assertNotNull(context.getStartupFailure()));
    }

    private void writePair(KeyPair pair) throws Exception {
        Files.writeString(directory.resolve("private.pem"), JwtTestKeys.pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Files.writeString(directory.resolve("public.pem"), JwtTestKeys.pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    }
}
