package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.dto.DoctorProfileResponse;
import com.booking.bookingsystem.dto.DoctorRegistrationRequest;
import com.booking.bookingsystem.service.DoctorRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/register/doctor")
@RequiredArgsConstructor
public class DoctorRegistrationController {
    private final DoctorRegistrationService registration;

    @PostMapping
    public ResponseEntity<DoctorProfileResponse> register(@RequestBody @Valid DoctorRegistrationRequest request) {
        var doctor = registration.register(request.name(), request.email(), request.password(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(DoctorProfileResponse.from(doctor));
    }
}
