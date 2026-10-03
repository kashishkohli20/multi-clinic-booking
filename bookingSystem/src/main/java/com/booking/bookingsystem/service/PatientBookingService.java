package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.Hold;
import com.booking.bookingsystem.entity.Appointment;
import com.booking.bookingsystem.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Application boundary for patient booking. Identity must come from verified authentication. */
@Service
@RequiredArgsConstructor
public class PatientBookingService {
    private final PatientRepository patients;
    private final LoginSessionService sessions;
    private final BookingService bookings;

    @Transactional
    public Hold holdSlot(UUID slotId, UUID accountId, UUID sessionId) {
        // Revalidate after taking the session lock, serializing booking with logout.
        // Activity, slot mutation and hold insertion commit or roll back together.
        sessions.recordActivity(sessionId, accountId);
        var patient = patients.findForEnabledAccount(accountId)
                .orElseThrow(() -> new AccessDeniedException("Patient profile required."));
        return bookings.holdSlot(slotId, patient.getId());
    }

    @Transactional
    public Appointment confirmBooking(UUID holdId, UUID accountId, UUID sessionId) {
        sessions.recordActivity(sessionId, accountId);
        var patient = patients.findForEnabledAccount(accountId)
                .orElseThrow(() -> new AccessDeniedException("Patient profile required."));
        return bookings.confirmBooking(holdId, patient.getId(), accountId);
    }
}
