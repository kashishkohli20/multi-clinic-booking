package com.booking.bookingsystem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.util.List;

@ConfigurationProperties("booking.auth.browser")
public record BrowserSecurityProperties(List<String> allowedOrigins, @DefaultValue("true") boolean secureCookies) {
    public BrowserSecurityProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        for (String origin : allowedOrigins) {
            URI uri = URI.create(origin);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !uri.getRawPath().isEmpty()
                    || uri.getPort() > 65535 || uri.getPort() == 0 || origin.contains("*")) {
                throw new IllegalArgumentException("CORS requires exact HTTP(S) origins without paths or wildcards");
            }
        }
    }
}
