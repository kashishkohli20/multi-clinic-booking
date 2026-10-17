package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.ClinicMembership;
import com.booking.bookingsystem.util.MembershipRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ClinicMembershipRepository extends JpaRepository<ClinicMembership, UUID> {
    // Deleted assignments retain their unique key and require explicit recovery.
    @Query(value = """
            select exists(select 1 from clinic_membership
              where user_id = :userId and clinic_id = :clinicId and role = 'DOCTOR'
                and (active = true or deleted = true))
            """, nativeQuery = true)
    boolean existsBlockingDoctorAssignment(@Param("userId") UUID userId, @Param("clinicId") UUID clinicId);

    /**
     * Checks clinic-scoped membership for an enabled account. Hibernate excludes
     * soft-deleted memberships, accounts and clinics through the explicit joins.
     * Doctor profile and resource ownership checks belong to the authorization layer.
     */
    @Query("""
            select count(m) > 0
            from ClinicMembership m
            join m.appUser u
            join m.clinic c
            where u.id = :userId
              and c.id = :clinicId
              and m.role = :role
              and m.active = true
              and u.enabled = true
            """)
    boolean existsActiveMembership(@Param("userId") UUID userId,
                                   @Param("clinicId") UUID clinicId,
                                   @Param("role") MembershipRole role);
}
