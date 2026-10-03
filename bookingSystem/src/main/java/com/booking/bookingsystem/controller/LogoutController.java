package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.dto.ApiErrorResponse;
import com.booking.bookingsystem.security.AuthenticationCookies;
import com.booking.bookingsystem.service.LogoutService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@RestController
@RequiredArgsConstructor
public class LogoutController {
    private final LogoutService logouts;
    private final AuthenticationCookies cookies;
    private final CookieCsrfTokenRepository csrf;
    private final Clock clock;

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        String refresh = null;
        String access = null;
        int refreshCount = 0;
        int accessCount = 0;
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (AuthenticationCookies.REFRESH.equals(cookie.getName())) {
                    refresh = cookie.getValue();
                    refreshCount++;
                } else if (AuthenticationCookies.ACCESS.equals(cookie.getName())) {
                    access = cookie.getValue();
                    accessCount++;
                }
            }
        }
        if (refreshCount > 1 || accessCount > 1) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new ApiErrorResponse(
                    clock.instant(), 400, "Bad Request", "Ambiguous authentication cookies.", request.getRequestURI()));
        }
        logouts.logout(refresh, access);
        // The transactional service has committed before the browser loses its credentials.
        cookies.clear(response);
        csrf.saveToken(null, request, response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiErrorResponse> unavailable(HttpServletRequest request) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(new ApiErrorResponse(
                clock.instant(), 503, "Service Unavailable", "Authentication temporarily unavailable.", request.getRequestURI()));
    }
}
