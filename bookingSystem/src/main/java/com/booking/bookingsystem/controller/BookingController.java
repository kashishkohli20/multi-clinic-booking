package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.dto.ConfirmationRequest;
import com.booking.bookingsystem.dto.SlotResponse;
import com.booking.bookingsystem.dto.HoldResponse;
import com.booking.bookingsystem.dto.AppointmentResponse;
import com.booking.bookingsystem.dto.SlotHoldRequest;
import com.booking.bookingsystem.entity.Appointment;
import com.booking.bookingsystem.entity.Hold;
import com.booking.bookingsystem.entity.Slot;
import com.booking.bookingsystem.service.BookingService;
import com.booking.bookingsystem.service.PatientBookingService;
import com.booking.bookingsystem.security.CookieAuthenticationFilter;
import com.booking.bookingsystem.service.exception.InvalidLoginSessionException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class BookingController {

    private final BookingService bookingService;
    private final PatientBookingService patientBookings;

    @PostMapping("/slots/hold")
    public ResponseEntity<HoldResponse> holdSlot(@RequestBody @Valid SlotHoldRequest slotHoldRequest,
            @AuthenticationPrincipal CookieAuthenticationFilter.Principal caller) {
        if (caller == null) throw new InvalidLoginSessionException();
        UUID slotId = slotHoldRequest.slotId();
        Hold hold = patientBookings.holdSlot(slotId, caller.accountId(), caller.sessionId());
        return ResponseEntity.status(HttpStatus.CREATED).body(HoldResponse.from(hold));
    }

    @GetMapping("/slots/search")
    public ResponseEntity<List<SlotResponse>> search(@RequestParam String doctorName) {
        log.info("Searching for doctor {}", doctorName);
        List<Slot> availableSlots = bookingService.findAvailableSlotsForDoctor(doctorName);
        return ResponseEntity.ok(availableSlots.stream().map(SlotResponse::from).toList());
    }

    @PostMapping("/confirm")
    public ResponseEntity<AppointmentResponse> confirmBooking(@RequestBody @Valid ConfirmationRequest confirmationRequest,
            @AuthenticationPrincipal CookieAuthenticationFilter.Principal caller) {
        if (caller == null) throw new InvalidLoginSessionException();
        UUID holdId = confirmationRequest.holdId();
        Appointment appointment = patientBookings.confirmBooking(holdId, caller.accountId(), caller.sessionId());
        return ResponseEntity.status(HttpStatus.CREATED).body(AppointmentResponse.from(appointment));
    }
}
