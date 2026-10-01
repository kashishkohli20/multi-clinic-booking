package com.booking.bookingsystem.util;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class EmailNormalizer {
    public String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email must not be blank");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
