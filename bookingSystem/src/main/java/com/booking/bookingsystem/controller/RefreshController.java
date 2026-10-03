package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.dto.ApiErrorResponse;
import com.booking.bookingsystem.dto.RefreshResponse;
import com.booking.bookingsystem.security.AuthenticationCookies;
import com.booking.bookingsystem.service.RefreshTokenService;
import com.booking.bookingsystem.service.exception.InvalidRefreshTokenException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@RestController
@RequiredArgsConstructor
public class RefreshController {
    private final RefreshTokenService tokens;
    private final AuthenticationCookies cookies;
    private final Clock clock;

    @PostMapping("/api/v1/auth/refresh")
    public ResponseEntity<RefreshResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        var result = tokens.rotateWithAccessToken(refreshCookie(request));
        // Rotation, including replay revocation, owns its commit boundary. No outer transaction here.
        Instant now = clock.instant();
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookies.access(result.access().value(), remaining(now, result.access().expiresAt())).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookies.refresh(result.refresh().value(), remaining(now, result.refresh().expiresAt())).toString());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RefreshResponse(
                result.refresh().accountId(), result.access().expiresAt(), result.refresh().expiresAt()));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiErrorResponse> invalidRefresh(HttpServletRequest request, HttpServletResponse response) {
        cookies.clear(response);
        return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).body(new ApiErrorResponse(
                clock.instant(), 401, "Unauthorized", "Invalid refresh token.", request.getRequestURI()));
    }

    @ExceptionHandler({DataAccessException.class, JwtEncodingException.class})
    public ResponseEntity<ApiErrorResponse> unavailable(HttpServletRequest request) {
        // Do not overwrite browser credentials on an infrastructure failure.
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(new ApiErrorResponse(
                clock.instant(), 503, "Service Unavailable", "Authentication temporarily unavailable.", request.getRequestURI()));
    }

    private String refreshCookie(HttpServletRequest request) {
        String value = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (AuthenticationCookies.REFRESH.equals(cookie.getName())) {
                    if (value != null) throw new InvalidRefreshTokenException();
                    value = cookie.getValue();
                }
            }
        }
        if (value == null) throw new InvalidRefreshTokenException();
        return value;
    }

    private Duration remaining(Instant now, Instant deadline) {
        return now.isBefore(deadline) ? Duration.between(now, deadline) : Duration.ZERO;
    }
}
