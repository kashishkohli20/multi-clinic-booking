package com.booking.bookingsystem.service.exception;

public class SessionCreationNotAllowedException extends RuntimeException {
    public SessionCreationNotAllowedException() {
        super("Unable to create a login session.");
    }
}
