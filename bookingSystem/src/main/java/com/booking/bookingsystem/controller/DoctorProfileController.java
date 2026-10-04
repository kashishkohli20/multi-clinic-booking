package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.dto.DoctorProfileRequest;
import com.booking.bookingsystem.dto.DoctorProfileResponse;
import com.booking.bookingsystem.security.CookieAuthenticationFilter;
import com.booking.bookingsystem.service.AuthenticatedDoctorProfileService;
import com.booking.bookingsystem.service.exception.InvalidLoginSessionException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/doctors/me")
@RequiredArgsConstructor
public class DoctorProfileController {
    private final AuthenticatedDoctorProfileService profiles;

    @PostMapping
    public ResponseEntity<DoctorProfileResponse> create(@Valid @RequestBody DoctorProfileRequest request,
            @AuthenticationPrincipal CookieAuthenticationFilter.Principal caller) {
        if (caller == null) throw new InvalidLoginSessionException();
        var response = profiles.create(caller.accountId(), caller.sessionId(), request.name(), request.description());
        return ResponseEntity.created(URI.create("/api/v1/doctors/me"))
                .cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping
    public ResponseEntity<DoctorProfileResponse> read(
            @AuthenticationPrincipal CookieAuthenticationFilter.Principal caller) {
        if (caller == null) throw new InvalidLoginSessionException();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(profiles.read(caller.accountId(), caller.sessionId()));
    }
}
