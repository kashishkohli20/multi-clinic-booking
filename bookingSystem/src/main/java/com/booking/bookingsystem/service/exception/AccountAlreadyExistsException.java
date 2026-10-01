package com.booking.bookingsystem.service.exception;

public class AccountAlreadyExistsException extends RuntimeException {
    public AccountAlreadyExistsException(Throwable cause) {
        super("An account with this email already exists.", cause);
    }
}
