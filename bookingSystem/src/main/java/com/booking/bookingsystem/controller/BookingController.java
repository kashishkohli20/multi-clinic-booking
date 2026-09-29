package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.dto.ConfirmationRequest;
import com.booking.bookingsystem.dto.SlotHoldRequest;
import com.booking.bookingsystem.entity.Appointment;
import com.booking.bookingsystem.dto.BookingRequest;
import com.booking.bookingsystem.entity.Hold;
import com.booking.bookingsystem.entity.Slot;
import com.booking.bookingsystem.service.BookingService;
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

    @PostMapping("/bookings")
    public ResponseEntity<Appointment> bookSlot(@RequestBody @Valid BookingRequest bookingRequest) {
        UUID slotId = bookingRequest.getSlotId();
        UUID clinicId = bookingRequest.getClinicId();
        UUID patientId = bookingRequest.getPatientId();

        Appointment appointment = bookingService.bookSlot(slotId, clinicId, patientId);
        return ResponseEntity.status(HttpStatus.CREATED).body(appointment);
    }

    @PostMapping("/slots/hold")
    public ResponseEntity<Hold> holdSlot(@RequestBody @Valid SlotHoldRequest slotHoldRequest) {
        UUID slotId = slotHoldRequest.getSlotId();
        UUID patientId = slotHoldRequest.getPatientId();
        Hold hold = bookingService.holdSlot(slotId, patientId);
        return ResponseEntity.status(HttpStatus.CREATED).body(hold);
    }

    @GetMapping("/slots/search")
    public ResponseEntity<List<Slot>> search(@RequestParam String doctorName) {
        log.info("Searching for doctor {}", doctorName);
        List<Slot> availableSlots = bookingService.findAvailableSlotsForDoctor(doctorName);
        return ResponseEntity.ok(availableSlots);
    }

    @PostMapping("/confirm")
    public ResponseEntity<Appointment> confirmBooking(@RequestBody @Valid ConfirmationRequest confirmationRequest) {
        UUID holdId = confirmationRequest.getHoldId();
        Appointment appointment = bookingService.confirmBooking(holdId);
        return ResponseEntity.status(HttpStatus.CREATED).body(appointment);
    }
}
