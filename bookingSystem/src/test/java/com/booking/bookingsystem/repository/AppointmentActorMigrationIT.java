package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.PostgresTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
class AppointmentActorMigrationIT {
    @Autowired DataSource dataSource;

    @Test
    void upgradePreservesOriginalIdsBackfillsLinkedAccountsAndEnforcesActorForeignKey() {
        // Isolated schema: exercise the actual V9 -> V10 upgrade with legacy data,
        // without resetting the application's schema or editing an applied migration.
        String schema = "actor_migration_" + UUID.randomUUID().toString().replace("-", "");
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("9").load().migrate();
        try {
            UUID account = UUID.randomUUID();
            UUID linkedPatient = UUID.randomUUID();
            UUID unlinkedPatient = UUID.randomUUID();
            UUID unknownLegacyActor = UUID.randomUUID();
            UUID clinic = UUID.randomUUID();
            UUID doctor = UUID.randomUUID();
            jdbc.update("insert into " + schema + ".app_user (id,name,email,enabled,deleted) values (?,?,?,false,true)",
                    account, "Legacy user", account + "@example.com");
            jdbc.update("insert into " + schema + ".patient (id,user_id,deleted) values (?,?,true)", linkedPatient, account);
            jdbc.update("insert into " + schema + ".patient (id) values (?)", unlinkedPatient);
            jdbc.update("insert into " + schema + ".clinic (id) values (?)", clinic);
            jdbc.update("insert into " + schema + ".doctor (id) values (?)", doctor);
            UUID[] actors = {linkedPatient, unlinkedPatient, unknownLegacyActor};
            UUID[] appointments = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
            for (int i = 0; i < actors.length; i++) {
                UUID slot = UUID.randomUUID();
                jdbc.update("insert into " + schema + ".slot (id,doctor_id,clinic_id,start_time,end_time,timezone,status,version,created_at,updated_at) "
                        + "values (?,?,?,now(),now()+interval '1 hour','UTC','BOOKED',0,now(),now())", slot, doctor, clinic);
                jdbc.update("insert into " + schema + ".appointment (id,patient_id,booked_by_id,booked_slot_id,status,created_at,updated_at,deleted) "
                        + "values (?,?,?,?,'CONFIRMED',now(),now(),?)", appointments[i], unlinkedPatient, actors[i], slot, i == 0);
            }

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();

            for (int i = 0; i < actors.length; i++) {
                var row = jdbc.queryForMap("select * from " + schema + ".appointment where id = ?", appointments[i]);
                assertEquals(actors[i], row.get("legacy_booked_by_patient_id"));
                assertEquals(i == 0 ? account : null, row.get("booked_by_id"));
                assertEquals(unlinkedPatient, row.get("patient_id"));
                assertEquals(i == 0, row.get("deleted"));
            }
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "update " + schema + ".appointment set booked_by_id = ? where id = ?", unlinkedPatient, appointments[1]));
            jdbc.update("update " + schema + ".appointment set booked_by_id = ? where id = ?", account, appointments[1]);
            assertEquals(account, jdbc.queryForObject("select booked_by_id from " + schema + ".appointment where id = ?", UUID.class, appointments[1]));
        } finally {
            jdbc.execute("drop schema " + schema + " cascade");
        }
    }
}
