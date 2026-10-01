package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.entity.Clinic;
import com.booking.bookingsystem.entity.ClinicMembership;
import com.booking.bookingsystem.util.MembershipRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@Transactional
class ClinicMembershipIT {
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @EnumSource(MembershipRole.class)
    void persistsRelationshipsAndRoleWithInactiveDefault(MembershipRole role) {
        ClinicMembership membership = createMembership(role);
        UUID id = membership.getId();
        UUID userId = membership.getAppUser().getId();
        UUID clinicId = membership.getClinic().getId();
        entityManager.clear();

        ClinicMembership loaded = entityManager.find(ClinicMembership.class, id);
        assertNotNull(loaded);
        assertEquals(userId, loaded.getAppUser().getId());
        assertEquals(clinicId, loaded.getClinic().getId());
        assertEquals(role, loaded.getRole());
        assertFalse(loaded.isActive());
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());
        assertEquals(role.name(), jdbc.queryForObject(
                "select role from clinic_membership where id = ?", String.class, id));
    }

    @Test
    void membershipCanBeActivatedRevokedAndReactivated() {
        UUID id = createMembership(MembershipRole.CLINIC_ADMIN).getId();
        for (boolean active : new boolean[]{true, false, true}) {
            entityManager.find(ClinicMembership.class, id).setActive(active);
            entityManager.flush();
            entityManager.clear();
            assertEquals(active, entityManager.find(ClinicMembership.class, id).isActive());
        }
    }

    @Test
    void deletingMembershipSoftDeletesItWithoutDeletingAccountOrClinic() {
        ClinicMembership membership = createMembership(MembershipRole.DOCTOR);
        UUID id = membership.getId();
        UUID userId = membership.getAppUser().getId();
        UUID clinicId = membership.getClinic().getId();
        entityManager.remove(membership);
        entityManager.flush();
        entityManager.clear();

        assertNull(entityManager.find(ClinicMembership.class, id));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select deleted from clinic_membership where id = ?", Boolean.class, id));
        assertNotNull(entityManager.find(AppUser.class, userId));
        assertNotNull(entityManager.find(Clinic.class, clinicId));
    }

    @Test
    void sqlInsertDefaultsToInactiveAndNotDeleted() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into clinic_membership (id, user_id, clinic_id, role) values (?, ?, ?, ?)",
                id, createUser().getId(), createClinic().getId(), "DOCTOR");

        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "select active from clinic_membership where id = ?", Boolean.class, id));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "select deleted from clinic_membership where id = ?", Boolean.class, id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_id", "clinic_id", "role", "active"})
    void databaseRejectsMissingRequiredFields(String column) {
        UUID userId = createUser().getId();
        UUID clinicId = createClinic().getId();
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> insertMembership(
                        column.equals("user_id") ? null : userId,
                        column.equals("clinic_id") ? null : clinicId,
                        column.equals("role") ? null : "DOCTOR",
                        column.equals("active") ? null : false, false));
        assertSqlState("23502", failure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATIENT", "doctor", ""})
    void databaseRejectsUnsupportedRoles(String role) {
        UUID userId = createUser().getId();
        UUID clinicId = createClinic().getId();
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> insertMembership(userId, clinicId, role, false, false));
        assertSqlState("23514", failure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "clinic"})
    void databaseRejectsReferencesToMissingRecords(String missing) {
        UUID userId = createUser().getId();
        UUID clinicId = createClinic().getId();
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> insertMembership(
                        missing.equals("user") ? UUID.randomUUID() : userId,
                        missing.equals("clinic") ? UUID.randomUUID() : clinicId,
                        "DOCTOR", false, false));
        assertSqlState("23503", failure);
    }

    @ParameterizedTest
    @CsvSource({"true, false", "false, false", "false, true"})
    void duplicateMembershipIsRejectedEvenWhenOriginalIsInactiveOrDeleted(boolean active, boolean deleted) {
        UUID userId = createUser().getId();
        UUID clinicId = createClinic().getId();
        insertMembership(userId, clinicId, "DOCTOR", active, deleted);

        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> insertMembership(userId, clinicId, "DOCTOR", true, false));
        assertSqlState("23505", failure);
    }

    @Test
    void differentUsersClinicsAndRolesCanHaveSeparateMemberships() {
        UUID userId = createUser().getId();
        UUID clinicId = createClinic().getId();
        UUID otherUserId = createUser().getId();
        UUID otherClinicId = createClinic().getId();

        UUID[] ids = {
                insertMembership(userId, clinicId, "DOCTOR", true, false),
                insertMembership(userId, clinicId, "CLINIC_ADMIN", true, false),
                insertMembership(userId, otherClinicId, "DOCTOR", true, false),
                insertMembership(otherUserId, clinicId, "DOCTOR", true, false)
        };
        for (UUID id : ids) {
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from clinic_membership where id = ?", Integer.class, id));
        }
    }

    @Test
    void updatingRoleCannotCreateDuplicateMembership() {
        UUID userId = createUser().getId();
        UUID clinicId = createClinic().getId();
        insertMembership(userId, clinicId, "DOCTOR", true, false);
        UUID adminId = insertMembership(userId, clinicId, "CLINIC_ADMIN", true, false);

        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update clinic_membership set role = 'DOCTOR' where id = ?", adminId));
        assertSqlState("23505", failure);
    }

    private AppUser createUser() {
        AppUser user = new AppUser();
        user.setEmail(UUID.randomUUID() + "@example.com");
        entityManager.persist(user);
        entityManager.flush();
        return user;
    }

    private Clinic createClinic() {
        Clinic clinic = new Clinic();
        clinic.setName("Test clinic");
        entityManager.persist(clinic);
        entityManager.flush();
        return clinic;
    }

    private ClinicMembership createMembership(MembershipRole role) {
        ClinicMembership membership = new ClinicMembership();
        membership.setAppUser(createUser());
        membership.setClinic(createClinic());
        membership.setRole(role);
        entityManager.persist(membership);
        entityManager.flush();
        return membership;
    }

    private UUID insertMembership(UUID userId, UUID clinicId, String role, Boolean active, boolean deleted) {
        UUID id = UUID.randomUUID();
        // Direct SQL checks Flyway constraints independently of Hibernate validation.
        jdbc.update("insert into clinic_membership (id, user_id, clinic_id, role, active, deleted) "
                        + "values (?, ?, ?, ?, ?, ?)",
                id, userId, clinicId, role, active, deleted);
        return id;
    }

    private void assertSqlState(String expected, DataIntegrityViolationException failure) {
        SQLException cause = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertEquals(expected, cause.getSQLState());
    }
}
