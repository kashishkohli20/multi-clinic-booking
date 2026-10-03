package com.booking.bookingsystem.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Credential validation belongs to the verifier so all credential failures remain generic. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(String email, String password) {
    @Override public String toString() { return "LoginRequest[REDACTED]"; }
}
