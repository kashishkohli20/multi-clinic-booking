package com.booking.bookingsystem.security;

import com.booking.bookingsystem.service.AccessTokenService;
import com.booking.bookingsystem.service.LoginSessionService;
import com.booking.bookingsystem.service.exception.InvalidAccessTokenException;
import com.booking.bookingsystem.service.exception.InvalidLoginSessionException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Created inside the security chain only, never registered as a servlet filter bean. */
public class CookieAuthenticationFilter extends OncePerRequestFilter {
    private final AccessTokenService tokens;
    private final LoginSessionService sessions;
    private static final Set<String> AUTH_POSTS = Set.of("/api/v1/auth/register", "/api/v1/auth/register/doctor", "/api/v1/auth/login",
            "/api/v1/auth/refresh", "/api/v1/auth/logout");

    public CookieAuthenticationFilter(AccessTokenService tokens, LoginSessionService sessions) {
        this.tokens = tokens;
        this.sessions = sessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Public routes do not depend on a usable access JWT. Refresh/logout will verify
        // their refresh credential separately, so expired access cookies cannot block them.
        return (Set.of("GET", "HEAD").contains(request.getMethod()) && Set.of("/actuator/health",
                "/api/v1/slots/search", "/api/v1/auth/csrf").contains(path))
                || (request.getMethod().equals("POST") && AUTH_POSTS.contains(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String value = null;
        int count = 0;
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (AuthenticationCookies.ACCESS.equals(cookie.getName())) {
                    value = cookie.getValue();
                    count++;
                }
            }
        }
        if (count > 1) {
            SecurityErrors.write(response, 401);
            return;
        }
        if (count == 1) {
            try {
                var identity = tokens.verify(value);
                sessions.requireValidSession(identity.sessionId(), identity.accountId());
                var principal = new Principal(identity.accountId(), identity.sessionId());
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
                SecurityContextHolder.setContext(context);
            } catch (InvalidAccessTokenException | InvalidLoginSessionException failure) {
                SecurityErrors.write(response, 401);
                return;
            } catch (DataAccessException failure) {
                SecurityErrors.write(response, 503);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    public record Principal(UUID accountId, UUID sessionId) { }
}
