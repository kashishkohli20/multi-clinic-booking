package com.booking.bookingsystem.service;

import com.booking.bookingsystem.dto.ClinicAccessRequestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthenticatedClinicAccessRequestService {
    private final LoginSessionService sessions;
    private final DoctorClinicAccessRequestService requests;

    @Transactional
    public ClinicAccessRequestResponse submit(UUID accountId, UUID sessionId, UUID clinicId) {
        // Session is always locked before the core service's account/profile locks.
        sessions.recordActivity(sessionId, accountId);
        return ClinicAccessRequestResponse.from(requests.submit(accountId, clinicId));
    }

    @Transactional(readOnly = true)
    public ClinicAccessRequestResponse read(UUID accountId, UUID sessionId, UUID requestId) {
        sessions.requireValidSession(sessionId, accountId);
        return ClinicAccessRequestResponse.from(requests.readOwn(accountId, requestId));
    }
}
