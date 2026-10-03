package com.booking.bookingsystem.service.exception;

public class InvalidLoginSessionException extends RuntimeException {
    public InvalidLoginSessionException() {
        super("Login session is not valid.");
    }
}
