package com.booking.bookingsystem.dto;

import com.booking.bookingsystem.entity.Appointment;
import java.util.UUID;

public record AppointmentResponse(UUID id, UUID bookedSlotId, UUID patientId, UUID bookedById, String status) {
    public static AppointmentResponse from(Appointment appointment) {
        return new AppointmentResponse(appointment.getId(), appointment.getBookedSlotId(),
                appointment.getPatientId(), appointment.getBookedById(), appointment.getStatus().name());
    }
}
