package com.booking.bookingsystem.config;

import com.booking.bookingsystem.security.AuthenticationCookies;
import com.booking.bookingsystem.security.CookieAuthenticationFilter;
import com.booking.bookingsystem.security.SecurityErrors;
import com.booking.bookingsystem.service.AccessTokenService;
import com.booking.bookingsystem.service.LoginSessionService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.util.List;
import java.util.function.Supplier;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(BrowserSecurityProperties.class)
public class BrowserSecurityConfiguration {
    @Bean
    public AuthenticationCookies authenticationCookies(BrowserSecurityProperties properties, Environment environment) {
        boolean localDev = environment.matchesProfiles("dev") && !environment.matchesProfiles("prod");
        if (!properties.secureCookies() && !localDev) {
            throw new IllegalArgumentException("Insecure cookies are permitted only in the dev profile, without prod");
        }
        for (String origin : properties.allowedOrigins()) {
            URI uri = URI.create(origin);
            if ("http".equals(uri.getScheme()) && !(localDev
                    && List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()))) {
                throw new IllegalArgumentException("Frontend origins require HTTPS except for localhost development");
            }
        }
        return new AuthenticationCookies(properties);
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository(BrowserSecurityProperties properties) {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieName(properties.secureCookies() ? "__Host-booking_csrf" : "booking_csrf");
        csrf.setCookiePath("/");
        csrf.setHeaderName("X-CSRF-TOKEN");
        csrf.setCookieCustomizer(builder -> builder.secure(properties.secureCookies()).httpOnly(true).sameSite("Lax"));
        return csrf;
    }

    @Bean
    public SecurityFilterChain browserSecurity(HttpSecurity http, AccessTokenService tokens,
                                               LoginSessionService sessions, BrowserSecurityProperties properties,
                                               AuthenticationCookies cookies, CookieCsrfTokenRepository csrf) throws Exception {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        cors.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);

        var xorHandler = new XorCsrfTokenRequestAttributeHandler();
        var csrfHandler = new CsrfTokenRequestHandler() {
            @Override
            public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> token) {
                xorHandler.handle(request, response, token);
            }

            @Override
            public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
                return request.getHeader(token.getHeaderName()) == null ? null : xorHandler.resolveCsrfTokenValue(request, token);
            }
        };

        http.cors(config -> config.configurationSource(source))
                .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(csrfHandler))
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(config -> config.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(config -> config.disable())
                .formLogin(config -> config.disable()).httpBasic(config -> config.disable()).logout(config -> config.disable())
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, failure) -> SecurityErrors.write(response, 401))
                        .accessDeniedHandler((request, response, failure) -> SecurityErrors.write(response, 403)))
                .authorizeHttpRequests(config -> config
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/api/v1/slots/search", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/actuator/health", "/api/v1/slots/search", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/slots/hold", "/api/v1/confirm").authenticated()
                        .anyRequest().denyAll())
                .addFilterAfter(new CookieAuthenticationFilter(tokens, sessions), CsrfFilter.class);
        return http.build();
    }
}
