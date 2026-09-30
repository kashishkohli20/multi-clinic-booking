package com.booking.bookingsystem;

import com.booking.bookingsystem.config.DataSeeder;
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

    @Test
    void productionStartsWithMigrationsAndWithoutDemoSeeder() {
        // Testcontainers supplies actual credentials; the placeholder URL cannot be used.
        assertTrue(context.getBeansOfType(DataSeeder.class).isEmpty());
        assertEquals("9090", environment.getProperty("server.port"));
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("never", environment.getProperty("server.error.include-message"));
        assertEquals(5, jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from patient", Integer.class));
    }
}
