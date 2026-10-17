package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.DoctorClinicAccessRequest;
import com.booking.bookingsystem.repository.*;
import com.booking.bookingsystem.service.exception.*;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Internal operations; account identity must come from verified authentication. */
@Service
@RequiredArgsConstructor
public class DoctorClinicAccessRequestService {
    private final AppUserRepository users;
    private final DoctorRepository doctors;
    private final ClinicRepository clinics;
    private final ClinicMembershipRepository memberships;
    private final DoctorClinicAccessRequestRepository requests;
    private final Clock clock;

    @Transactional
    public DoctorClinicAccessRequest submit(UUID accountId, UUID clinicId) {
        if (accountId == null) throw new AccessDeniedException("Access denied.");
        if (clinicId == null) throw new InvalidClinicAccessRequestException();
        // Stable eligibility and ordering: account -> doctor -> clinic. Future
        // membership decision services must coordinate on the same doctor lock.
        users.lockEnabledById(accountId).orElseThrow(() -> new AccessDeniedException("Access denied."));
        var doctor = doctors.lockForAccount(accountId)
                .orElseThrow(() -> new AccessDeniedException("Doctor profile required."));
        clinics.lockById(clinicId).orElseThrow(() -> new ResourceNotFoundException("Clinic not found."));
        if (memberships.existsBlockingDoctorAssignment(accountId, clinicId)
                || requests.existsByDoctorIdAndClinicIdAndStatus(doctor.getId(), clinicId, DoctorClinicAccessRequest.Status.PENDING)) {
            throw new ClinicAccessRequestConflictException();
        }
        var request = new DoctorClinicAccessRequest();
        request.setDoctorId(doctor.getId());
        request.setClinicId(clinicId);
        request.setRequestedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        try {
            return requests.saveAndFlush(request);
        } catch (DataIntegrityViolationException failure) {
            // The unique index remains authoritative even for writers outside this service.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())
                        && "uq_doctor_clinic_request_pending".equals(violation.getConstraintName())) {
                    throw new ClinicAccessRequestConflictException(failure);
                }
            }
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public DoctorClinicAccessRequest readOwn(UUID accountId, UUID requestId) {
        if (accountId == null) throw new AccessDeniedException("Access denied.");
        var doctor = doctors.findForEnabledAccount(accountId)
                .orElseThrow(() -> new AccessDeniedException("Doctor profile required."));
        if (requestId == null) throw new ResourceNotFoundException("Clinic access request not found.");
        return requests.findByIdAndDoctorId(requestId, doctor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Clinic access request not found."));
    }
}
