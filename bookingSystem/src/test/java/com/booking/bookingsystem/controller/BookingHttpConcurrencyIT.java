package com.booking.bookingsystem.controller;

import com.booking.bookingsystem.entity.Doctor;
import com.booking.bookingsystem.entity.Patient;
import com.booking.bookingsystem.repository.DoctorRepository;
import com.booking.bookingsystem.repository.PatientRepository;

import com.booking.bookingsystem.PostgresTestConfiguration;
import com.booking.bookingsystem.entity.*;
import com.booking.bookingsystem.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, BookingHttpConcurrencyIT.FixedTime.class})
class BookingHttpConcurrencyIT {
    @Autowired DoctorRepository doctors;
    @Autowired PatientRepository patientsRepository;

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final int CLIENTS = 10;
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        // Preserve Phase 1 HTTP business-concurrency coverage while production denies
        // these writes pending authenticated ownership. This bean exists only in this test.
        @Bean @Order(0)
        SecurityFilterChain legacyBookingTestChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/api/v1/slots/hold", "/api/v1/confirm")
                    .csrf(config -> config.disable())
                    .authorizeHttpRequests(config -> config.anyRequest().permitAll()).build();
        }

        @Bean @Primary
        Clock testClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @Value("${local.server.port}") int port;
    @Autowired ClinicRepository clinics;
    @Autowired SlotRepository slots;
    @Autowired HoldRepository holds;
    @Autowired AppointmentRepository appointments;
    @Autowired ObjectMapper json;
    private Slot slot;

    @BeforeEach
    void createCommittedSlot() {
        // No test transaction: each repository save commits before HTTP threads run.
        Clinic clinic = clinics.saveAndFlush(new Clinic());
        slot = new Slot();
        slot.setClinic(clinic);
        slot.setDoctorId(doctors.saveAndFlush(new Doctor()).getId());
        slot.setStartTime(NOW.plusSeconds(3600));
        slot.setEndTime(NOW.plusSeconds(7200));
        slot.setTimezone("Australia/Melbourne");
        slot.setStatus(Slot.Status.OPEN);
        slot = slots.saveAndFlush(slot);
    }

    private HttpRequest request(String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private List<HttpResponse<String>> concurrently(List<HttpRequest> requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CLIENTS);
        CountDownLatch ready = new CountDownLatch(CLIENTS);
        CountDownLatch start = new CountDownLatch(1);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            List<Future<HttpResponse<String>>> tasks = new ArrayList<>();
            for (HttpRequest request : requests) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new TimeoutException("Start gate timed out");
                    return client.send(request, HttpResponse.BodyHandlers.ofString());
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Clients did not become ready");
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (var task : tasks) {
                responses.add(task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return responses;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private JsonNode assertOneWinner(List<HttpResponse<String>> responses, String path) throws Exception {
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 201).count(), responses.toString());
        assertEquals(CLIENTS - 1, responses.stream().filter(r -> r.statusCode() == 409).count(),
                () -> responses.stream().map(r -> r.statusCode() + ": " + r.body()).toList().toString());
        for (var response : responses) {
            if (response.statusCode() == 409) {
                JsonNode error = json.readTree(response.body());
                assertEquals(409, error.get("status").asInt());
                assertEquals("/api/v1" + path, error.get("path").asText());
            }
        }
        return json.readTree(responses.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow().body());
    }

    @Test
    void concurrentHttpHoldsHaveOneWinnerAndNoOrphanRows() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        Set<UUID> patients = new HashSet<>();
        for (int i = 0; i < CLIENTS; i++) {
            UUID patient = patientsRepository.saveAndFlush(new Patient()).getId();
            patients.add(patient);
            requests.add(request("/slots/hold", "{\"slotId\":\"%s\",\"patientId\":\"%s\"}"
                    .formatted(slot.getId(), patient)));
        }
        JsonNode winner = assertOneWinner(concurrently(requests), "/slots/hold");
        var saved = holds.findAll().stream().filter(h -> slot.getId().equals(h.getSlotId())).toList();
        assertEquals(1, saved.size());
        Hold hold = saved.getFirst();
        assertEquals(Hold.Status.ACTIVE, hold.getStatus());
        assertTrue(patients.contains(hold.getHoldById()));
        assertEquals(hold.getId().toString(), winner.get("id").asText());
        assertEquals(hold.getHoldById().toString(), winner.get("holdById").asText());
        assertEquals(Slot.Status.HELD, slots.findById(slot.getId()).orElseThrow().getStatus());
        assertEquals(0, appointments.findAll().stream().filter(a -> slot.getId().equals(a.getBookedSlotId())).count());
    }

    @Test
    void concurrentHttpConfirmationsCreateExactlyOneAppointment() throws Exception {
        UUID patient = patientsRepository.saveAndFlush(new Patient()).getId();
        JsonNode held;
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request("/slots/hold", "{\"slotId\":\"%s\",\"patientId\":\"%s\"}"
                    .formatted(slot.getId(), patient)), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
            held = json.readTree(response.body());
        }
        UUID holdId = UUID.fromString(held.get("id").asText());
        HttpRequest confirmation = request("/confirm", "{\"holdId\":\"%s\"}".formatted(holdId));
        JsonNode winner = assertOneWinner(concurrently(Collections.nCopies(CLIENTS, confirmation)), "/confirm");
        var saved = appointments.findAll().stream().filter(a -> slot.getId().equals(a.getBookedSlotId())).toList();
        assertEquals(1, saved.size());
        assertEquals(Appointment.Status.CONFIRMED, saved.getFirst().getStatus());
        assertEquals(patient, saved.getFirst().getPatientId());
        assertEquals(saved.getFirst().getId().toString(), winner.get("id").asText());
        assertEquals(Hold.Status.CONFIRMED, holds.findById(holdId).orElseThrow().getStatus());
        assertEquals(Slot.Status.BOOKED, slots.findById(slot.getId()).orElseThrow().getStatus());
    }

    @Test
    void unknownPatientReturns404WithoutCreatingHold() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request("/slots/hold", "{\"slotId\":\"%s\",\"patientId\":\"%s\"}"
                    .formatted(slot.getId(), UUID.randomUUID())), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode(), response.body());
        }
        assertEquals(Slot.Status.OPEN, slots.findById(slot.getId()).orElseThrow().getStatus());
        assertEquals(0, holds.findAll().stream().filter(h -> slot.getId().equals(h.getSlotId())).count());
    }

    private JsonNode search(HttpClient client, String name) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/slots/search?doctorName=" + name)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    @Test
    void searchHoldConfirmWorksWithoutAssistantAndFiltersUnavailableSlots() throws Exception {
        String name = "Doctor" + UUID.randomUUID().toString().replace("-", "");
        Doctor doctor = doctors.findById(slot.getDoctorId()).orElseThrow();
        doctor.setName(name);
        doctors.saveAndFlush(doctor);
        Doctor other = new Doctor();
        other.setName(name);
        other = doctors.saveAndFlush(other);
        Slot later = new Slot();
        later.setClinic(slot.getClinic());
        later.setDoctorId(other.getId());
        later.setTimezone("UTC");
        later.setStatus(Slot.Status.OPEN);
        later.setStartTime(NOW.plusSeconds(10800));
        later.setEndTime(NOW.plusSeconds(14400));
        later = slots.saveAndFlush(later);
        for (long offset : new long[] {-3600, 0}) {
            Slot past = new Slot();
            past.setClinic(slot.getClinic());
            past.setDoctorId(doctor.getId());
            past.setTimezone("UTC");
            past.setStatus(Slot.Status.OPEN);
            past.setStartTime(NOW.plusSeconds(offset));
            past.setEndTime(NOW.plusSeconds(offset + 1800));
            slots.saveAndFlush(past);
        }
        UUID patient = patientsRepository.saveAndFlush(new Patient()).getId();
        try (HttpClient client = HttpClient.newHttpClient()) {
            JsonNode results = search(client, name);
            assertEquals(2, results.size());
            assertEquals(slot.getId().toString(), results.get(0).get("id").asText());
            assertEquals(later.getId().toString(), results.get(1).get("id").asText());
            var held = client.send(request("/slots/hold", "{\"slotId\":\"%s\",\"patientId\":\"%s\"}"
                    .formatted(results.get(0).get("id").asText(), patient)), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, held.statusCode(), held.body());
            assertEquals(1, search(client, name).size());
            UUID holdId = UUID.fromString(json.readTree(held.body()).get("id").asText());
            var confirmed = client.send(request("/confirm", "{\"holdId\":\"%s\"}".formatted(holdId)),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, confirmed.statusCode(), confirmed.body());
            assertEquals(1, search(client, name).size());
            assertEquals(Slot.Status.BOOKED, slots.findById(slot.getId()).orElseThrow().getStatus());
            assertEquals(Hold.Status.CONFIRMED, holds.findById(holdId).orElseThrow().getStatus());
        }
    }
}
