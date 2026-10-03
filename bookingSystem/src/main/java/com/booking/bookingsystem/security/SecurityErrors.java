package com.booking.bookingsystem.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class SecurityErrors {
    private SecurityErrors() { }

    public static void write(HttpServletResponse response, int status) throws IOException {
        String message = switch (status) {
            case 401 -> "Authentication required.";
            case 403 -> "Access denied.";
            default -> "Authentication temporarily unavailable.";
        };
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
