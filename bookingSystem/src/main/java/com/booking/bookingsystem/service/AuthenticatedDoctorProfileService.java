package com.booking.bookingsystem.service;

import com.booking.bookingsystem.dto.DoctorProfileResponse;
import com.booking.bookingsystem.repository.DoctorRepository;
import com.booking.bookingsystem.service.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Account/session IDs must come from verified authentication at the HTTP boundary. */
@Service
@RequiredArgsConstructor
public class AuthenticatedDoctorProfileService {
    private final LoginSessionService sessions;
    private final DoctorProfileService profiles;
    private final DoctorRepository doctors;

    @Transactional
    public DoctorProfileResponse create(UUID accountId, UUID sessionId, String name, String description) {
        // Lock and revalidate against logout/expiry after filter authentication.
        // Activity and profile insertion roll back together on validation/conflict/failure.
        sessions.recordActivity(sessionId, accountId);
        return DoctorProfileResponse.from(profiles.createProfile(accountId, name, description));
    }

    @Transactional(readOnly = true)
    public DoctorProfileResponse read(UUID accountId, UUID sessionId) {
        sessions.requireValidSession(sessionId, accountId);
        return doctors.findForEnabledAccount(accountId)
                .map(DoctorProfileResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found."));
    }
}
