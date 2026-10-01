package com.booking.bookingsystem.util;

import com.booking.bookingsystem.service.exception.InvalidRegistrationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class NameNormalizerTest {
    private final NameNormalizer normalizer = new NameNormalizer();

    @Test
    void stripsSurroundingWhitespaceAndPreservesNameSpelling() {
        assertEquals("Élodie  O'Neill-Smith", normalizer.validateAndNormalizeName("\t\u2003Élodie  O'Neill-Smith\n"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsNullOrBlankNames(String name) {
        assertThrows(InvalidRegistrationException.class, () -> normalizer.validateAndNormalizeName(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void accepts255CodePointsAfterTrimming(String character) {
        String name = character.repeat(255);
        assertEquals(name, normalizer.validateAndNormalizeName(" " + name + " "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void rejectsMoreThan255CodePoints(String character) {
        assertThrows(InvalidRegistrationException.class,
                () -> normalizer.validateAndNormalizeName(character.repeat(256)));
    }
}
