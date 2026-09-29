package com.booking.bookingsystem.service;

import com.booking.bookingsystem.entity.*;
import com.booking.bookingsystem.repository.*;
import com.booking.bookingsystem.service.exception.ResourceNotFoundException;
import com.booking.bookingsystem.service.exception.SlotUnavailableException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final SlotRepository slotRepository;
    private final HoldRepository holdRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final Clock clock;
    private final Duration holdDuration;

    @Transactional
    public Hold holdSlot(UUID slotId, UUID holdById) {

        Slot slot =  slotRepository.findById(slotId).orElseThrow(() -> new ResourceNotFoundException("Slot not found"));

        if (slot.getStatus() != Slot.Status.OPEN) {
            throw new SlotUnavailableException("Slot is not open");
        }

        Instant now = clock.instant();
        if (!slot.getStartTime().isAfter(now)) {
            throw new SlotUnavailableException("The slot has already started.");
        }

        if (!patientRepository.existsById(holdById)) {
            throw new ResourceNotFoundException("Patient not found");
        }

        slot.setStatus(Slot.Status.HELD);
        slotRepository.save(slot);

        Instant expiresAt = now.plus(holdDuration);

        Hold hold = new Hold();
        hold.setSlotId(slotId);
        hold.setStatus(Hold.Status.ACTIVE);
        hold.setHoldById(holdById);
        hold.setExpiresAt(expiresAt);
        holdRepository.save(hold);

        return hold;
    }

    public List<Slot> findAvailableSlotsForDoctor(String doctorName) {
        Doctor doctor = doctorRepository.findByNameContainingIgnoreCase(doctorName).orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));

        List<Slot> slots = new ArrayList<>();
        List<Slot> openSlots = slotRepository.findByDoctorIdAndStatus(doctor.getId(), Slot.Status.OPEN);
        List<Slot> bookedSlots = slotRepository.findByDoctorIdAndStatus(doctor.getId(), Slot.Status.BOOKED);
        slots.addAll(openSlots);
        slots.addAll(bookedSlots);
        return openSlots;
//        return slots;
    }

    @Transactional
    public Appointment confirmBooking(UUID holdId) {
        // 1. Fetch and validate the active Hold record
        Hold hold = holdRepository.findById(holdId)
                .orElseThrow(() -> new ResourceNotFoundException("Hold record not found with ID: " + holdId));

        if (hold.getStatus() != Hold.Status.ACTIVE) {
            throw new SlotUnavailableException("This reservation cannot be confirmed because its status is: " + hold.getStatus());
        }

        Instant now = clock.instant();
        if (!hold.getExpiresAt().isAfter(now)) {
            throw new SlotUnavailableException("This reservation hold has already expired.");
        }

        // 2. Fetch the corresponding Slot
        Slot slot = slotRepository.findById(hold.getSlotId())
                .orElseThrow(() -> new ResourceNotFoundException("Slot not found with ID: " + hold.getSlotId()));

        // Double check that the slot is still in HELD status
        if (slot.getStatus() != Slot.Status.HELD) {
            throw new SlotUnavailableException("The slot is no longer reserved for confirmation.");
        }

        // 3. Mutate states atomically
        hold.setStatus(Hold.Status.CONFIRMED);
        holdRepository.save(hold);

        slot.setStatus(Slot.Status.BOOKED);
        slotRepository.save(slot); // Increments version tracker automatically

        // 4. Create and persist the official permanent Appointment record
        Appointment appointment = new Appointment();
        appointment.setBookedSlotId(slot.getId());
        appointment.setPatientId(hold.getHoldById());
        appointment.setBookedById(hold.getHoldById());
        appointment.setStatus(Appointment.Status.CONFIRMED);

        return appointmentRepository.save(appointment);
    }
}
