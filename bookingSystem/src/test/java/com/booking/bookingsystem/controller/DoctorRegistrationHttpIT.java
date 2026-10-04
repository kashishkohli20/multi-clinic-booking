package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.dto.DoctorRegistrationRequest;
import com.booking.bookingsystem.repository.AppUserRepository;
import com.booking.bookingsystem.service.DoctorProfileService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DoctorRegistrationHttpIT {
    private static final String PATH = "/api/v1/auth/register/doctor";
    private static final String PASSWORD = "Doctor signup passphrase!";
    private final String email = UUID.randomUUID() + "@example.com";
    private final UUID legacyDoctor = UUID.randomUUID();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @MockitoSpyBean DoctorProfileService profiles;
    private Cookie csrfCookie;
    private String csrfToken;

    @BeforeEach
    void csrf() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        csrfCookie = response.getCookie("__Host-booking_csrf");
        csrfToken = json.readTree(response.getContentAsString()).get("token").asText();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from refresh_token where session_id in (select id from login_session where user_id in (select id from app_user where email=?))", email);
        jdbc.update("delete from login_session where user_id in (select id from app_user where email=?)", email);
        jdbc.update("delete from doctor where user_id in (select id from app_user where email=?) or id=?", email, legacyDoctor);
        jdbc.update("delete from app_user where email=?", email);
    }

    @Test
    void publicSignupReturnsSafeDtoWithoutLoggingInAndExistingLoginWorks() throws Exception {
        var body = validBody();
        body.put("email", "  " + email.toUpperCase(Locale.ROOT) + " ");
        body.put("name", "  Dr Smith  ");
        body.put("description", "  Family medicine  ");
        var response = register(body);
        assertEquals(201, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        var result = json.readTree(response.getContentAsString());
        assertEquals(5, result.size());
        assertEquals("Dr Smith", result.get("name").asText());
        assertEquals("Family medicine", result.get("description").asText());
        assertNotNull(result.get("createdAt"));
        assertNotNull(result.get("updatedAt"));
        UUID doctor = UUID.fromString(result.get("id").asText());
        UUID account = jdbc.queryForObject("select user_id from doctor where id=?", UUID.class, doctor);
        var user = users.findById(account).orElseThrow();
        assertEquals(email, user.getEmail());
        assertTrue(user.isEnabled());
        assertTrue(encoder.matches(PASSWORD, user.getPasswordHash()));
        assertFalse(response.getContentAsString().contains(PASSWORD));
        assertFalse(response.getContentAsString().contains(user.getPasswordHash()));
        assertNull(response.getCookie("booking_access"));
        assertNull(response.getCookie("booking_refresh"));
        assertEquals(0, count("login_session"));
        assertEquals(0, count("patient"));
        assertEquals(0, count("clinic_membership"));

        var login = mvc.perform(post("/api/v1/auth/login").cookie(csrfCookie).header("X-CSRF-TOKEN", csrfToken)
                .contentType("application/json").content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie access = login.getCookie("booking_access");
        assertNotNull(access);
        var own = mvc.perform(get("/api/v1/doctors/me").cookie(access))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(result, json.readTree(own.getContentAsString()));
        csrf();
        // A Doctor profile confers neither a Patient profile nor booking privileges.
        mvc.perform(post("/api/v1/slots/hold").cookie(access, csrfCookie).header("X-CSRF-TOKEN", csrfToken)
                .contentType("application/json").content(json.writeValueAsString(Map.of("slotId", UUID.randomUUID()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void suppliedIdentityRoleAndClinicFieldsCannotClaimLegacyDoctorOrGrantAccess() throws Exception {
        jdbc.update("insert into doctor (id,name) values (?,?)", legacyDoctor, "Dr Smith");
        UUID suppliedAccount = UUID.randomUUID();
        var body = validBody();
        body.put("doctorId", legacyDoctor);
        body.put("id", legacyDoctor);
        body.put("accountId", suppliedAccount);
        body.put("appUser", Map.of("id", suppliedAccount));
        body.put("clinicId", UUID.randomUUID());
        body.put("role", "CLINIC_ADMIN");
        body.put("active", true);
        body.put("enabled", false);
        body.put("passwordHash", "attacker-supplied-hash");
        var response = register(body);
        assertEquals(201, response.getStatus());
        assertNotEquals(legacyDoctor.toString(), json.readTree(response.getContentAsString()).get("id").asText());
        assertNull(jdbc.queryForObject("select user_id from doctor where id=?", UUID.class, legacyDoctor));
        var user = users.findByNormalizedEmail(email).orElseThrow();
        assertNotEquals(suppliedAccount, user.getId());
        assertTrue(user.isEnabled());
        assertTrue(encoder.matches(PASSWORD, user.getPasswordHash()));
        assertEquals(0, count("patient"));
        assertEquals(0, count("clinic_membership"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "email", "password"})
    void missingRequiredFieldsReturn400WithoutAccount(String field) throws Exception {
        var body = validBody();
        body.remove(field);
        var response = register(body);
        assertEquals(400, response.getStatus());
        assertFalse(response.getContentAsString().contains(PASSWORD));
        assertEquals(0, accountCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "email", "password", "description"})
    void invalidInputReturns400AndRollsBackAccount(String field) throws Exception {
        var body = validBody();
        body.put(field, switch (field) {
            case "name" -> "😀".repeat(256);
            case "email" -> "invalid-email";
            case "password" -> "short";
            default -> "😀".repeat(256);
        });
        var response = register(body);
        assertEquals(400, response.getStatus());
        assertFalse(response.getContentAsString().contains(PASSWORD));
        assertEquals(0, accountCount());
        assertEquals(0, count("doctor"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "disabled", "deleted"})
    void duplicateEmailReturns409WithoutChangingExistingAccount(String state) throws Exception {
        assertEquals(201, register(validBody()).getStatus());
        if (state.equals("disabled")) jdbc.update("update app_user set enabled=false where email=?", email);
        if (state.equals("deleted")) jdbc.update("update app_user set deleted=true where email=?", email);
        var before = jdbc.queryForMap("select * from app_user where email=?", email);
        var body = validBody();
        body.put("email", email.toUpperCase(Locale.ROOT));
        var response = register(body);
        assertEquals(409, response.getStatus());
        assertEquals("An account with this email already exists.", json.readTree(response.getContentAsString()).get("message").asText());
        assertEquals(before, jdbc.queryForMap("select * from app_user where email=?", email));
        assertEquals(1, count("doctor"));
        assertFalse(response.getContentAsString().contains("uq_app_user"));
    }

    @Test
    void csrfAndCorsApplyEvenThoughRegistrationIsPublic() throws Exception {
        String body = json.writeValueAsString(validBody());
        mvc.perform(post(PATH).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(PATH).cookie(csrfCookie).header("X-CSRF-TOKEN", "invalid")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(PATH).cookie(csrfCookie).header("X-CSRF-TOKEN", csrfToken).header("Origin", "https://untrusted.example")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        assertEquals(0, accountCount());
    }

    @Test
    void unusableAccessCookieDoesNotBlockPublicSignup() throws Exception {
        mvc.perform(post(PATH).cookie(csrfCookie, new Cookie("booking_access", "unusable-old-access-token"))
                .header("X-CSRF-TOKEN", csrfToken).contentType("application/json").content(json.writeValueAsString(validBody())))
                .andExpect(status().isCreated());
        assertEquals(1, count("doctor"));
        assertEquals(0, count("login_session"));
    }

    @Test
    void malformedJsonDoesNotEchoCredentialsOrCreateAccount() throws Exception {
        var response = mvc.perform(post(PATH).cookie(csrfCookie).header("X-CSRF-TOKEN", csrfToken)
                .contentType("application/json").content("{\"password\":\"" + PASSWORD + "\", broken}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertFalse(response.getContentAsString().contains(PASSWORD));
        assertEquals(0, accountCount());
    }

    @Test
    void storageFailureReturnsGenericErrorWithoutAccountOrCookies() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataIntegrityViolationException("Simulated after-insert failure");
        }).when(profiles).createProfile(any(), any(), any());
        var response = register(validBody());
        assertEquals(500, response.getStatus());
        assertFalse(response.getContentAsString().contains("Simulated"));
        assertNull(response.getCookie("booking_access"));
        assertNull(response.getCookie("booking_refresh"));
        assertEquals(0, accountCount());
        assertEquals(0, count("doctor"));
    }

    @Test
    void requestToStringDoesNotExposeRegistrationDetails() {
        var request = new DoctorRegistrationRequest("Dr Smith", email, PASSWORD, "Private text");
        assertEquals("DoctorRegistrationRequest[REDACTED]", request.toString());
    }

    private Map<String, Object> validBody() {
        return new HashMap<>(Map.of("name", "Dr Smith", "email", email, "password", PASSWORD));
    }
    private MockHttpServletResponse register(Map<String, Object> body) throws Exception {
        return mvc.perform(post(PATH).cookie(csrfCookie).header("X-CSRF-TOKEN", csrfToken)
                .contentType("application/json").content(json.writeValueAsString(body))).andReturn().getResponse();
    }
    private int accountCount() {
        return jdbc.queryForObject("select count(*) from app_user where email=?", Integer.class, email);
    }
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id in (select id from app_user where email=?)", Integer.class, email);
    }
}
