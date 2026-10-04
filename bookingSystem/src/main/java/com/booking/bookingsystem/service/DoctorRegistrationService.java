package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.Doctor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DoctorRegistrationService {
    private final AccountCreationService accounts;
    private final DoctorProfileService profiles;

    /** Creates a new account and profile atomically; never attaches to an existing account. */
    @Transactional
    public Doctor register(String name, String email, String password, String description) {
        var account = accounts.createAccount(name, email, password);
        // This is a newly inserted, managed account. Enabling it remains uncommitted
        // until the profile succeeds; the profile query flushes the managed change.
        account.setEnabled(true);
        return profiles.createProfile(account.getId(), account.getName(), description);
    }
}
