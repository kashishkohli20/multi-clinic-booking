package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.Doctor;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.repository.DoctorRepository;
import com.booking.bookingsystem.service.exception.DoctorProfileAlreadyExistsException;
import com.booking.bookingsystem.service.exception.InvalidRegistrationException;
import com.booking.bookingsystem.util.NameNormalizer;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DoctorProfileService {
    private final AppUserRepository users;
    private final DoctorRepository doctors;
    private final NameNormalizer nameNormalizer;

    /**
     * Internal operation: accountId must come from verified authentication or the
     * new account created by DoctorRegistrationService, never a client-selected
     * account. Joins the caller's transaction.
     * AuthenticatedDoctorProfileService validates the session and returns a DTO
     * for the HTTP boundary, not this entity.
     * Creates no account, patient or clinic membership and never claims an existing profile.
     */
    @Transactional
    public Doctor createProfile(UUID accountId, String name, String description) {
        if (accountId == null) {
            throw new AccessDeniedException("Access denied.");
        }
        var account = users.lockEnabledById(accountId)
                .orElseThrow(() -> new AccessDeniedException("Access denied."));

        var doctor = new Doctor();
        doctor.setAppUser(account);
        doctor.setName(nameNormalizer.validateAndNormalizeName(name));
        doctor.setDescription(normalizeDescription(description));
        try {
            // V1's unique key includes soft-deleted profiles and protects all writers.
            return doctors.saveAndFlush(doctor);
        } catch (DataIntegrityViolationException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())
                        && "doctor_user_id_key".equals(violation.getConstraintName())) {
                    throw new DoctorProfileAlreadyExistsException(failure);
                }
            }
            throw failure;
        }
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String cleaned = description.strip();
        if (cleaned.codePointCount(0, cleaned.length()) > 255) {
            throw new InvalidRegistrationException("Description must not exceed 255 characters.");
        }
        // Stored as plain text; any future UI must render it as text, not HTML.
        return cleaned;
    }
}
