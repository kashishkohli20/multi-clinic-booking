package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("test")
class PatientRegistrationHttpIT {
    private static final String PATH = "/api/v1/auth/register";
    private static final String PASSWORD = "A sample passphrase longer than twenty!";
    private final String email = UUID.randomUUID() + "@example.com";
    private final UUID existingPatientId = UUID.randomUUID();

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from patient where user_id in (select id from app_user where email = ?) or id = ?",
                email, existingPatientId);
        jdbc.update("delete from app_user where email = ?", email);
    }

    @Test
    void returns201WithOnlySafeFieldsAndCommitsRegistration() throws Exception {
        Map<String, Object> body = validRequest();
        body.put("name", "  Alice Smith  ");
        body.put("email", "  " + email.toUpperCase(Locale.ROOT) + "  ");
        var response = post(body);
        assertEquals(201, response.statusCode(), response.body());
        JsonNode result = json.readTree(response.body());
        assertEquals(3, result.size());
        assertEquals("Alice Smith", result.get("name").asText());
        UUID patientId = UUID.fromString(result.get("id").asText());
        UUID userId = UUID.fromString(result.get("userId").asText());
        var account = users.findById(userId).orElseThrow();
        assertEquals(email, account.getEmail());
        assertEquals("Alice Smith", account.getName());
        assertTrue(account.isEnabled());
        assertTrue(encoder.matches(PASSWORD, account.getPasswordHash()));
        assertEquals(userId, jdbc.queryForObject("select user_id from patient where id = ?", UUID.class, patientId));
        assertFalse(response.body().contains(PASSWORD));
        assertFalse(response.body().contains(account.getPasswordHash()));
    }

    static Stream<Arguments> missingOrBlankFields() {
        return Stream.of("name", "email", "password").flatMap(field ->
                Stream.of("missing", "null", "blank").map(kind -> Arguments.of(field, kind)));
    }

    @ParameterizedTest
    @MethodSource("missingOrBlankFields")
    void requiredFieldsReturn400(String field, String kind) throws Exception {
        Map<String, Object> body = validRequest();
        switch (kind) {
            case "missing" -> body.remove(field);
            case "null" -> body.put(field, null);
            case "blank" -> body.put(field, " \t ");
            default -> throw new IllegalArgumentException(kind);
        }
        var response = post(body);
        assertEquals(400, response.statusCode(), response.body());
        assertNotNull(json.readTree(response.body()).get("errors").get(field));
        assertFalse(response.body().contains(PASSWORD));
        assertNoAccount();
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("name", "a".repeat(256), "Name must not exceed 255 characters."),
                Arguments.of("email", "not-an-email", "A valid email of at most 255 characters is required."),
                Arguments.of("password", "short", "Password must contain at least 15 characters and not be blank."),
                Arguments.of("password", "😀".repeat(8), "Password must contain at least 15 characters and not be blank."),
                Arguments.of("password", "x".repeat(73), "Password must not exceed 72 UTF-8 bytes."),
                Arguments.of("password", "é".repeat(37), "Password must not exceed 72 UTF-8 bytes."));
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void serviceValidationReturns400WithSafeMessage(String field, String value, String message) throws Exception {
        Map<String, Object> body = validRequest();
        body.put(field, value);
        var response = post(body);
        assertError(response, 400, message);
        assertFalse(response.body().contains(value));
        assertFalse(response.body().contains(PASSWORD));
        assertNoAccount();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ascii", "unicode"})
    void acceptsNameAndPasswordAtServiceLimits(String kind) throws Exception {
        String name = (kind.equals("ascii") ? "a" : "😀").repeat(255);
        String password = kind.equals("ascii") ? "x".repeat(72) : "é".repeat(36);
        Map<String, Object> body = validRequest();
        body.put("name", "  " + name + "  ");
        body.put("password", password);
        var response = post(body);
        assertEquals(201, response.statusCode(), response.body());
        assertEquals(name, json.readTree(response.body()).get("name").asText());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateEmailReturns409IncludingDeletedAccounts(boolean deleted) throws Exception {
        var original = post(validRequest());
        assertEquals(201, original.statusCode(), original.body());
        UUID userId = UUID.fromString(json.readTree(original.body()).get("userId").asText());
        if (deleted) {
            jdbc.update("update app_user set deleted = true where id = ?", userId);
        }
        Map<String, Object> body = validRequest();
        body.put("email", " " + email.toUpperCase(Locale.ROOT) + " ");
        var response = post(body);
        assertError(response, 409, "An account with this email already exists.");
        assertFalse(response.body().contains(email));
        assertFalse(response.body().contains(PASSWORD));
        assertFalse(response.body().contains("uq_app_user"));
        assertEquals(1, accountCount());
        assertEquals(1, jdbc.queryForObject("select count(*) from patient where user_id = ?", Integer.class, userId));
    }

    @Test
    void extraFieldsCannotAssignRolesClaimPatientOrOverrideAccountState() throws Exception {
        jdbc.update("insert into patient (id, name) values (?, ?)", existingPatientId, "Existing patient");
        UUID suppliedUserId = UUID.randomUUID();
        Map<String, Object> body = validRequest();
        body.put("id", existingPatientId);
        body.put("patientId", existingPatientId);
        body.put("userId", suppliedUserId);
        body.put("role", "CLINIC_ADMIN");
        body.put("roles", new String[]{"DOCTOR", "CLINIC_ADMIN"});
        body.put("clinicId", UUID.randomUUID());
        body.put("enabled", false);
        body.put("passwordHash", "attacker-controlled-hash");
        body.put("appUser", Map.of("id", suppliedUserId, "enabled", false));

        var response = post(body);
        assertEquals(201, response.statusCode(), response.body());
        JsonNode result = json.readTree(response.body());
        assertNotEquals(existingPatientId.toString(), result.get("id").asText());
        UUID userId = UUID.fromString(result.get("userId").asText());
        assertNotEquals(suppliedUserId, userId);
        var user = users.findById(userId).orElseThrow();
        assertTrue(user.isEnabled());
        assertTrue(encoder.matches(PASSWORD, user.getPasswordHash()));
        assertNull(jdbc.queryForObject("select user_id from patient where id = ?", UUID.class, existingPatientId));
        assertEquals(0, jdbc.queryForObject("select count(*) from clinic_membership where user_id = ?", Integer.class, userId));
        assertEquals(0, jdbc.queryForObject("select count(*) from doctor where user_id = ?", Integer.class, userId));
    }

    @Test
    void malformedJsonReturns400WithoutEchoingPassword() throws Exception {
        var response = postRaw("{\"password\":\"" + PASSWORD + "\", broken}");
        assertEquals(400, response.statusCode());
        assertFalse(response.body().contains(PASSWORD));
        assertNoAccount();
    }

    private Map<String, Object> validRequest() {
        return new HashMap<>(Map.of("name", "Alice Smith", "email", email, "password", PASSWORD));
    }

    private HttpResponse<String> post(Map<String, Object> body) throws Exception {
        return postRaw(json.writeValueAsString(body));
    }

    private HttpResponse<String> postRaw(String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var bootstrap = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/csrf"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, bootstrap.statusCode());
            var csrf = json.readTree(bootstrap.body());
            String cookie = bootstrap.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .header("Cookie", cookie).header(csrf.get("headerName").asText(), csrf.get("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertError(HttpResponse<String> response, int status, String message) {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode error = json.readTree(response.body());
        assertEquals(status, error.get("status").asInt());
        assertEquals(message, error.get("message").asText());
        assertEquals(PATH, error.get("path").asText());
    }

    private int accountCount() {
        return jdbc.queryForObject("select count(*) from app_user where email = ?", Integer.class, email);
    }

    private void assertNoAccount() {
        assertEquals(0, accountCount());
    }
}
