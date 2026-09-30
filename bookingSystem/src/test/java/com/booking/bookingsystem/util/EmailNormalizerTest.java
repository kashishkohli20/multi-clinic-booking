package com.booking.bookingsystem.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmailNormalizerTest {
    private final EmailNormalizer normalizer = new EmailNormalizer();

    @ParameterizedTest
    @CsvSource({
            "'  Alice@Example.COM  ', alice@example.com",
            "'A.Lice+Booking@GMAIL.COM', a.lice+booking@gmail.com",
            "'A.Lice+Booking@GOOGLEMAIL.COM', a.lice+booking@googlemail.com",
            "'alice@example.com', alice@example.com",
            "' NOT-AN-EMAIL ', not-an-email"
    })
    void normalizesWithoutProviderSpecificRewritingOrFormatValidation(String input, String expected) {
        assertEquals(expected, normalizer.normalize(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsNullAndBlankInput(String input) {
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(input));
    }

    @Test
    void stripsSurroundingWhitespace() {
        assertEquals("alice@example.com", normalizer.normalize("\t\u2003Alice@Example.com\n"));
    }

    @Test
    void repeatedNormalizationDoesNotChangeResult() {
        String normalized = normalizer.normalize("  A.Lice+Booking@Example.COM  ");
        assertEquals(normalized, normalizer.normalize(normalized));
    }
}
