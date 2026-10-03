package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.dto.LoginRequest;
import com.booking.bookingsystem.dto.LoginResponse;
import com.booking.bookingsystem.security.AuthenticationCookies;
import com.booking.bookingsystem.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@RestController
@RequiredArgsConstructor
public class LoginController {
    private final LoginService logins;
    private final AuthenticationCookies cookies;
    private final CookieCsrfTokenRepository csrf;
    private final Clock clock;

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest credentials,
                                               HttpServletRequest request, HttpServletResponse response) {
        var result = logins.login(credentials.email(), credentials.password());
        Instant now = clock.instant();
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookies.access(result.access().value(), remaining(now, result.access().expiresAt())).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookies.refresh(result.refresh().value(), remaining(now, result.refresh().expiresAt())).toString());
        // The browser must bootstrap fresh CSRF state after this authentication boundary.
        csrf.saveToken(null, request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LoginResponse(
                result.accountId(), result.access().expiresAt(), result.refresh().expiresAt()));
    }

    private Duration remaining(Instant now, Instant deadline) {
        return now.isBefore(deadline) ? Duration.between(now, deadline) : Duration.ZERO;
    }
}
