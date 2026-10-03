package com.booking.bookingsystem.service.exception;

public class InvalidAccessTokenException extends RuntimeException {
    public InvalidAccessTokenException() {
        super("Invalid access token.");
    }
}
