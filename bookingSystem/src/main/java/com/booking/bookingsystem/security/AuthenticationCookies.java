package com.booking.bookingsystem.security;

import com.booking.bookingsystem.config.BrowserSecurityProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/** Shared issuance/deletion scope for the upcoming login, refresh, and logout endpoints. */
public class AuthenticationCookies {
    public static final String ACCESS = "booking_access";
    public static final String REFRESH = "booking_refresh";
    private final boolean secure;

    public AuthenticationCookies(BrowserSecurityProperties properties) {
        secure = properties.secureCookies();
    }

    public ResponseCookie access(String value, Duration maxAge) { return cookie(ACCESS, "/api", value, maxAge); }
    public ResponseCookie refresh(String value, Duration maxAge) { return cookie(REFRESH, "/api/v1/auth", value, maxAge); }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, access("", Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refresh("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String name, String path, String value, Duration maxAge) {
        if (maxAge == null || maxAge.isNegative()) throw new IllegalArgumentException("Cookie max-age must not be negative");
        return ResponseCookie.from(name, value).path(path).httpOnly(true).secure(secure)
                .sameSite("Lax").maxAge(maxAge).build();
    }
}
