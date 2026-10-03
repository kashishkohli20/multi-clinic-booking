package com.booking.bookingsystem;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.booking.bookingsystem.security.JwtTestKeys;
import org.springframework.test.context.DynamicPropertyRegistrar;
import java.nio.file.Files;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {
    @Bean
    DynamicPropertyRegistrar jwtTestProperties() throws Exception {
        var keys = JwtTestKeys.generate(2048);
        var directory = Files.createTempDirectory("booking-test-jwt-");
        var privateKey = directory.resolve("private.pem");
        var publicKey = directory.resolve("public.pem");
        Files.writeString(privateKey, JwtTestKeys.pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
        Files.writeString(publicKey, JwtTestKeys.pem("PUBLIC KEY", keys.getPublic().getEncoded()));
        directory.toFile().deleteOnExit();
        privateKey.toFile().deleteOnExit();
        publicKey.toFile().deleteOnExit();
        return registry -> {
            registry.add("booking.auth.jwt.enabled", () -> "true");
            registry.add("booking.auth.jwt.issuer", () -> "https://issuer.example.test");
            registry.add("booking.auth.jwt.audience", () -> "booking-api");
            registry.add("booking.auth.jwt.private-key", () -> privateKey.toUri().toString());
            registry.add("booking.auth.jwt.public-key", () -> publicKey.toUri().toString());
        };
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }
}
