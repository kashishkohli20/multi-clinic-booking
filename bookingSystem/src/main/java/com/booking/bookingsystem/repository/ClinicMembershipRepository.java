package com.booking.bookingsystem.repository;

import com.booking.bookingsystem.entity.ClinicMembership;
import com.booking.bookingsystem.util.MembershipRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ClinicMembershipRepository extends JpaRepository<ClinicMembership, UUID> {
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
