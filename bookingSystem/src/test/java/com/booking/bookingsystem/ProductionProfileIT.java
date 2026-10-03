package com.booking.bookingsystem;

import com.booking.bookingsystem.config.DataSeeder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("prod")
@Import(PostgresTestConfiguration.class)
@SpringBootTest(properties = {
    "DB_URL=jdbc:postgresql://localhost:1/unused",
    "DB_USERNAME=unused", "DB_PASSWORD=unused", "PORT=9090",
    "booking.expiry.enabled=false"
})
class ProductionProfileIT {
    @Autowired ApplicationContext context;
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void productionUsesExpectedConfiguration() {
        // Testcontainers supplies actual credentials; the placeholder URL cannot be used.
        assertEquals("9090", environment.getProperty("server.port"));
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("never", environment.getProperty("server.error.include-message"));
    }

    @Test
    void productionAppliesAllDiscoveredMigrations() {
        var migrations = flyway.info();
        // Avoid a vacuous pass if no migrations were discovered at all.
        assertTrue(migrations.applied().length > 0, "Expected applied database migrations");
        assertEquals(0, migrations.pending().length, "All discovered migrations should be applied at startup");
        // Successful context startup also verifies Hibernate's schema validation.
    }

    @Test
    void productionStartsWithoutDemoSeederOrSeededData() {
        assertTrue(context.getBeansOfType(DataSeeder.class).isEmpty());
        assertEquals(0, jdbc.queryForObject("select count(*) from refresh_token", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from login_session", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from patient", Integer.class));
    }
}
