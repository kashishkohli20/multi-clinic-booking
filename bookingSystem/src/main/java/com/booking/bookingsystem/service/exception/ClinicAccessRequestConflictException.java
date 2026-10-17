package com.booking.bookingsystem.service.exception;

public class ClinicAccessRequestConflictException extends RuntimeException {
    public ClinicAccessRequestConflictException() { this(null); }
    public ClinicAccessRequestConflictException(Throwable cause) {
        super("A pending request or existing doctor assignment prevents this request.", cause);
    }
}
