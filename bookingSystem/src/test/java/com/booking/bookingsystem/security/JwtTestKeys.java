package com.booking.bookingsystem.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/** Generates disposable keys at test runtime; no signing credentials are checked in. */
public final class JwtTestKeys {
    private JwtTestKeys() { }

    public static KeyPair generate(int bits) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }
}
