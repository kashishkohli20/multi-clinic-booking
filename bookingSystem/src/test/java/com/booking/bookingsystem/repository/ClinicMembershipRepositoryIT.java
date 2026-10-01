package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.entity.Clinic;
import com.booking.bookingsystem.entity.ClinicMembership;
import com.booking.bookingsystem.util.MembershipRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@Transactional
class ClinicMembershipRepositoryIT {
    @Autowired ClinicMembershipRepository memberships;
    @Autowired EntityManager entityManager;

    @ParameterizedTest
    @EnumSource(MembershipRole.class)
    void findsActiveMembershipForExactAccountClinicAndRole(MembershipRole role) {
        ClinicMembership membership = createMembership(role);
        assertTrue(hasMembership(membership));
    }

    @Test
    void roleAtOneClinicDoesNotGrantItAtAnotherClinic() {
        ClinicMembership admin = createMembership(MembershipRole.CLINIC_ADMIN);
        ClinicMembership doctor = new ClinicMembership();
        doctor.setAppUser(admin.getAppUser());
        doctor.setClinic(createClinic());
        doctor.setRole(MembershipRole.DOCTOR);
        doctor.setActive(true);
        memberships.saveAndFlush(doctor);

        assertTrue(hasMembership(admin));
        assertTrue(hasMembership(doctor));
        assertFalse(memberships.existsActiveMembership(admin.getAppUser().getId(),
                doctor.getClinic().getId(), MembershipRole.CLINIC_ADMIN));
        assertFalse(memberships.existsActiveMembership(admin.getAppUser().getId(),
                admin.getClinic().getId(), MembershipRole.DOCTOR));
    }

    @Test
    void anotherAccountCannotUseExistingMembership() {
        ClinicMembership membership = createMembership(MembershipRole.CLINIC_ADMIN);
        AppUser other = createUser();
        assertFalse(memberships.existsActiveMembership(other.getId(),
                membership.getClinic().getId(), membership.getRole()));
    }

    @Test
    void missingAccountOrClinicHasNoMembership() {
        ClinicMembership membership = createMembership(MembershipRole.CLINIC_ADMIN);
        assertFalse(memberships.existsActiveMembership(UUID.randomUUID(),
                membership.getClinic().getId(), membership.getRole()));
        assertFalse(memberships.existsActiveMembership(membership.getAppUser().getId(),
                UUID.randomUUID(), membership.getRole()));
    }

    @Test
    void revocationAndReactivationAreReflectedInLookup() {
        ClinicMembership membership = createMembership(MembershipRole.CLINIC_ADMIN);
        assertTrue(hasMembership(membership));
        membership.setActive(false);
        entityManager.flush();
        assertFalse(hasMembership(membership));
        membership.setActive(true);
        entityManager.flush();
        assertTrue(hasMembership(membership));
    }

    @Test
    void disabledAccountHasNoEligibleMembershipUntilReenabled() {
        ClinicMembership membership = createMembership(MembershipRole.CLINIC_ADMIN);
        membership.getAppUser().setEnabled(false);
        entityManager.flush();
        assertFalse(hasMembership(membership));
        membership.getAppUser().setEnabled(true);
        entityManager.flush();
        assertTrue(hasMembership(membership));
    }

    @ParameterizedTest
    @ValueSource(strings = {"membership", "account", "clinic"})
    void softDeletedRecordsAreExcluded(String deletedRecord) {
        ClinicMembership membership = createMembership(MembershipRole.CLINIC_ADMIN);
        UUID userId = membership.getAppUser().getId();
        UUID clinicId = membership.getClinic().getId();
        assertTrue(hasMembership(membership));

        // Delete in a fresh persistence context, without a managed membership
        // still referencing the account or clinic being removed.
        entityManager.clear();
        switch (deletedRecord) {
            case "membership" -> entityManager.remove(entityManager.find(ClinicMembership.class, membership.getId()));
            case "account" -> entityManager.remove(entityManager.find(AppUser.class, userId));
            case "clinic" -> entityManager.remove(entityManager.find(Clinic.class, clinicId));
            default -> throw new IllegalArgumentException(deletedRecord);
        }
        entityManager.flush();
        entityManager.clear();

        assertFalse(memberships.existsActiveMembership(userId, clinicId, MembershipRole.CLINIC_ADMIN));
    }

    private boolean hasMembership(ClinicMembership membership) {
        return memberships.existsActiveMembership(membership.getAppUser().getId(),
                membership.getClinic().getId(), membership.getRole());
    }

    private AppUser createUser() {
        AppUser user = new AppUser();
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setEnabled(true);
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
        membership.setActive(true);
        return memberships.saveAndFlush(membership);
    }
}
