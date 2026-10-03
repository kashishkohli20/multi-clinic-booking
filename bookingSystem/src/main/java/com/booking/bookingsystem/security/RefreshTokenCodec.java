package com.booking.bookingsystem.security;

import com.booking.bookingsystem.service.exception.InvalidRefreshTokenException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class RefreshTokenCodec {
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String token) {
        // Accept exactly our canonical 256-bit, unpadded base64url representation.
        if (token == null || token.length() != 43 || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new InvalidRefreshTokenException();
        }
        byte[] decoded = Base64.getUrlDecoder().decode(token);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token)) {
            throw new InvalidRefreshTokenException();
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
