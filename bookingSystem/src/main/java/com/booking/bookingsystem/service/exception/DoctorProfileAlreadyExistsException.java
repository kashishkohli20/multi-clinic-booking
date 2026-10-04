package com.booking.bookingsystem.service.exception;

public class DoctorProfileAlreadyExistsException extends RuntimeException {
    public DoctorProfileAlreadyExistsException(Throwable cause) {
        super("A Doctor profile already exists for this account.", cause);
    }
}
