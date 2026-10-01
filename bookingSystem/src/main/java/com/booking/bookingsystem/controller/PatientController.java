package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.dto.PatientRegistrationRequest;
import com.booking.bookingsystem.dto.PatientRegistrationResponse;
import com.booking.bookingsystem.entity.Patient;
import com.booking.bookingsystem.service.PatientRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PatientController {

    private final PatientRegistrationService patientRegistrationService;

    @PostMapping("/auth/register")
    public ResponseEntity<PatientRegistrationResponse> registerPatient(@RequestBody @Valid PatientRegistrationRequest patientRegistrationRequest) {
        String name = patientRegistrationRequest.name();
        String email = patientRegistrationRequest.email();
        String password = patientRegistrationRequest.password();

        Patient patient = patientRegistrationService.createPatient(name, email, password);
        return ResponseEntity.status(HttpStatus.CREATED).body(PatientRegistrationResponse.from(patient));
    }

}
