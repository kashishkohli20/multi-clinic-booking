package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.dto.ClinicAccessRequestInput;
import com.booking.bookingsystem.dto.ClinicAccessRequestResponse;
import com.booking.bookingsystem.security.CookieAuthenticationFilter;
import com.booking.bookingsystem.service.AuthenticatedClinicAccessRequestService;
import com.booking.bookingsystem.service.exception.InvalidLoginSessionException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/doctors/me/clinic-requests")
@RequiredArgsConstructor
public class ClinicAccessRequestController {
    private final AuthenticatedClinicAccessRequestService requests;

    @PostMapping
    public ResponseEntity<ClinicAccessRequestResponse> submit(@RequestBody @Valid ClinicAccessRequestInput input,
            @AuthenticationPrincipal CookieAuthenticationFilter.Principal caller) {
        if (caller == null) throw new InvalidLoginSessionException();
        var response = requests.submit(caller.accountId(), caller.sessionId(), input.clinicId());
        return ResponseEntity.created(URI.create("/api/v1/doctors/me/clinic-requests/" + response.id()))
                .cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<ClinicAccessRequestResponse> read(@PathVariable UUID requestId,
            @AuthenticationPrincipal CookieAuthenticationFilter.Principal caller) {
        if (caller == null) throw new InvalidLoginSessionException();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(requests.read(caller.accountId(), caller.sessionId(), requestId));
    }
}
