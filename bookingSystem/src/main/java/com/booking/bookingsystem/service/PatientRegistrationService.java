package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.AppUser;
import com.booking.bookingsystem.entity.Patient;
import com.booking.bookingsystem.repository.PatientRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PatientRegistrationService {

    private final AccountCreationService accountCreationService;
    private final PatientRepository patientRepository;

    @Transactional
    public Patient createPatient(String name, String email, String password) {
        AppUser user = accountCreationService.createAccount(name, email, password);

        Patient patient = new Patient();
        patient.setName(user.getName());
        patient.setAppUser(user);

        Patient savedPatient = patientRepository.save(patient);
        user.setEnabled(true); // Hibernate remembers changes to entities it manages while a transaction is open. This is called dirty checking

        return savedPatient;
    }

}
