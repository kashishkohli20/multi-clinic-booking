package com.booking.bookingsystem.security;

import com.booking.bookingsystem.service.exception.InvalidRefreshTokenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class RefreshTokenCodecTest {
    private final RefreshTokenCodec codec = new RefreshTokenCodec();

    @Test
    void generatesIndependent256BitValuesAndHashesTheirExactRepresentation() throws Exception {
        String value = codec.generate();
        assertEquals(43, value.length());
        assertEquals(32, Base64.getUrlDecoder().decode(value).length);
        assertNotEquals(value, codec.generate());
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(expected, codec.hash(value));
        assertEquals(expected, codec.hash(value));
        assertTrue(expected.matches("[0-9a-f]{64}"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-token", "noncanonical", "padded", "leading-space", "too-long", "invalid-character"})
    void rejectsMalformedValuesWithoutEchoingThem(String scenario) {
        String value = scenario == null ? null : switch (scenario) {
            case "noncanonical" -> "A".repeat(42) + "B"; // Nonzero unused base64 bits.
            case "padded" -> codec.generate() + "=";
            case "leading-space" -> " " + codec.generate();
            case "too-long" -> "A".repeat(10000);
            case "invalid-character" -> "A".repeat(42) + "+";
            default -> scenario;
        };
        var failure = assertThrows(InvalidRefreshTokenException.class, () -> codec.hash(value));
        assertEquals("Invalid refresh token.", failure.getMessage());
        assertNull(failure.getCause());
    }
}
