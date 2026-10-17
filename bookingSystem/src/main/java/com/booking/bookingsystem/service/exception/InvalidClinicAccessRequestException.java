package com.booking.bookingsystem.service.exception;

public class InvalidClinicAccessRequestException extends IllegalArgumentException {
    public InvalidClinicAccessRequestException() { super("Clinic ID is required."); }
}
