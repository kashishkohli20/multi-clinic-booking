package com.booking.bookingsystem.util;

import com.booking.bookingsystem.service.exception.InvalidRegistrationException;
import org.springframework.stereotype.Component;

@Component
public class NameNormalizer {
    public String validateAndNormalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidRegistrationException("Name must not be blank.");
        }
        String cleanedName = name.strip();
        if (cleanedName.codePointCount(0, cleanedName.length()) > 255) {
            throw new InvalidRegistrationException("Name must not exceed 255 characters.");
        }
        return cleanedName;
    }
}
