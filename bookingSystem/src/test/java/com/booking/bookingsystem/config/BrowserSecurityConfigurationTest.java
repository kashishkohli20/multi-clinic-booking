package com.booking.bookingsystem.config;

import com.booking.bookingsystem.security.AuthenticationCookies;
import com.booking.bookingsystem.service.LoginSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BrowserSecurityConfigurationTest {
    @Test
    void authenticationCookiesUseMatchingSecureHostOnlyIssuanceAndDeletionScopes() {
        var cookies = new AuthenticationCookies(new BrowserSecurityProperties(List.of(), true));
        var access = cookies.access("access-value", Duration.ofMinutes(15));
        var refresh = cookies.refresh("refresh-value", Duration.ofHours(8));
        for (var cookie : List.of(access, refresh)) {
            assertTrue(cookie.isHttpOnly());
            assertTrue(cookie.isSecure());
            assertEquals("Lax", cookie.getSameSite());
            assertNull(cookie.getDomain());
        }
        assertEquals("/api", access.getPath());
        assertEquals("/api/v1/auth", refresh.getPath());
        assertEquals(Duration.ofMinutes(15), access.getMaxAge());
        assertEquals(Duration.ofHours(8), refresh.getMaxAge());
        var response = new MockHttpServletResponse();
        cookies.clear(response);
        assertEquals(2, response.getHeaders("Set-Cookie").size());
        for (var issued : List.of(access, refresh)) {
            var deleted = response.getCookie(issued.getName());
            assertNotNull(deleted);
            assertEquals("", deleted.getValue());
            assertEquals(0, deleted.getMaxAge());
            assertEquals(issued.getPath(), deleted.getPath());
            assertNull(deleted.getDomain());
            assertTrue(deleted.getSecure());
            assertTrue(deleted.isHttpOnly());
            assertEquals("Lax", deleted.getAttribute("SameSite"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "default", "test", "dev,prod"})
    void insecureCookiesCannotBeUsedOutsideExclusiveDevelopment(String profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles.split(","));
        assertThrows(IllegalArgumentException.class, () -> new BrowserSecurityConfiguration()
                .authenticationCookies(new BrowserSecurityProperties(List.of(), false), environment));
    }

    @Test
    void localhostDevelopmentExplicitlyPermitsHttp() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        var cookies = new BrowserSecurityConfiguration().authenticationCookies(
                new BrowserSecurityProperties(List.of("http://localhost:5173"), false), environment);
        assertFalse(cookies.access("value", Duration.ofMinutes(15)).isSecure());
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.com", "null", "https://app.example.com/", "https://app.example.com/path",
            "https://app.example.com?x=1", "https://user@app.example.com", "https://app.example.com#fragment", "https://app.example.com:99999"})
    void rejectsNonOriginCorsConfiguration(String value) {
        assertThrows(IllegalArgumentException.class, () -> new BrowserSecurityProperties(List.of(value), true));
    }

    @Test
    void productionRejectsHttpAndDevelopmentRejectsRemoteHttp() {
        var production = new MockEnvironment();
        production.setActiveProfiles("prod");
        var development = new MockEnvironment();
        development.setActiveProfiles("dev");
        var config = new BrowserSecurityConfiguration();
        assertThrows(IllegalArgumentException.class, () -> config.authenticationCookies(
                new BrowserSecurityProperties(List.of("http://localhost:5173"), true), production));
        assertThrows(IllegalArgumentException.class, () -> config.authenticationCookies(
                new BrowserSecurityProperties(List.of("http://app.example.com"), false), development));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void jwtDisabledOrMissingKeysFailsStartupRatherThanAllowingAnonymousFallback(boolean enabled) {
        new WebApplicationContextRunner()
                .withUserConfiguration(BrowserSecurityConfiguration.class, AccessTokenConfiguration.class, ReservationConfiguration.class)
                .withBean(LoginSessionService.class, () -> mock(LoginSessionService.class))
                .withPropertyValues("booking.auth.jwt.enabled=" + enabled)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
