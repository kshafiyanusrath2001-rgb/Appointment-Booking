package com.example.appointments.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.appointments.Appointment;
import com.example.appointments.AppointmentService;
import com.example.appointments.Concurrently;
import com.example.appointments.InMemoryAppointmentStore;
import com.example.appointments.SlowAppointmentStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Integration tests: real HTTP requests against a server on an ephemeral port. */
class AppointmentHttpServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private InMemoryAppointmentStore store;
    private AppointmentHttpServer server;

    private void startServer(InMemoryAppointmentStore backingStore) throws IOException {
        store = backingStore;
        server = new AppointmentHttpServer(new AppointmentService(store), "127.0.0.1", 0, 16);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private record Reply(int status, JsonNode body) {
    }

    private Reply send(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), JSON.readTree(response.body()));
    }

    private Reply book(String providerId, String start, String end) throws Exception {
        return send("POST", "/appointments", String.format(
                "{\"providerId\":\"%s\",\"startTime\":\"%s\",\"endTime\":\"%s\"}", providerId, start, end));
    }

    private Reply book() throws Exception {
        return book("p1", "2026-01-15T10:00:00Z", "2026-01-15T11:00:00Z");
    }

    @Test
    void bookReturns201WithAppointment() throws Exception {
        startServer(new InMemoryAppointmentStore());
        Reply reply = book();

        assertEquals(201, reply.status());
        assertEquals("p1", reply.body().get("providerId").asText());
        assertEquals("2026-01-15T10:00:00Z", reply.body().get("startTime").asText());
        assertEquals("2026-01-15T11:00:00Z", reply.body().get("endTime").asText());
        assertEquals("ACTIVE", reply.body().get("status").asText());
        assertFalse(reply.body().get("appointmentId").asText().isBlank());
    }

    @Test
    void responseTimestampsAreNormalisedToUtc() throws Exception {
        startServer(new InMemoryAppointmentStore());
        Reply reply = book("p1", "2026-01-15T15:30:00+05:30", "2026-01-15T16:30:00+05:30");

        assertEquals(201, reply.status());
        assertEquals("2026-01-15T10:00:00Z", reply.body().get("startTime").asText());
        assertEquals("2026-01-15T11:00:00Z", reply.body().get("endTime").asText());
    }

    @Test
    void conflictingBookingReturns409() throws Exception {
        startServer(new InMemoryAppointmentStore());
        String firstId = book().body().get("appointmentId").asText();

        Reply reply = book("p1", "2026-01-15T10:30:00Z", "2026-01-15T11:30:00Z");

        assertEquals(409, reply.status());
        assertEquals("BOOKING_CONFLICT", reply.body().at("/error/code").asText());
        assertEquals(firstId, reply.body().at("/error/conflictingAppointmentId").asText());
        assertEquals(1, store.findAll().size());
    }

    @Test
    void adjacentBookingReturns201() throws Exception {
        startServer(new InMemoryAppointmentStore());
        book();
        assertEquals(201, book("p1", "2026-01-15T11:00:00Z", "2026-01-15T12:00:00Z").status());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "providerId|{\"startTime\":\"2026-01-15T10:00:00Z\",\"endTime\":\"2026-01-15T11:00:00Z\"}",
            "providerId|{\"providerId\":123,\"startTime\":\"2026-01-15T10:00:00Z\",\"endTime\":\"2026-01-15T11:00:00Z\"}",
            "startTime|{\"providerId\":\"p1\",\"endTime\":\"2026-01-15T11:00:00Z\"}",
            "endTime|{\"providerId\":\"p1\",\"startTime\":\"2026-01-15T10:00:00Z\"}",
            "startTime|{\"providerId\":\"p1\",\"startTime\":\"2026-01-15T10:00:00\",\"endTime\":\"2026-01-15T11:00:00Z\"}",
            "endTime|{\"providerId\":\"p1\",\"startTime\":\"2026-01-15T10:00:00Z\",\"endTime\":\"2026-01-15T10:00:00Z\"}",
            "endTime|{\"providerId\":\"p1\",\"startTime\":\"9999-12-31T18:00:00-05:00\",\"endTime\":\"9999-12-31T19:00:00-05:00\"}",
    })
    void validationErrorReturns400WithField(String testCase) throws Exception {
        startServer(new InMemoryAppointmentStore());
        String[] parts = testCase.split("\\|", 2);

        Reply reply = send("POST", "/appointments", parts[1]);

        assertEquals(400, reply.status());
        assertEquals("VALIDATION_ERROR", reply.body().at("/error/code").asText());
        assertEquals(parts[0], reply.body().at("/error/field").asText());
        assertEquals(List.of(), store.findAll());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{not json", "[]", "\"p1\"", "", "{\"providerId\":\"p1\"} trailing"})
    void malformedBodyReturns400(String body) throws Exception {
        startServer(new InMemoryAppointmentStore());

        Reply reply = send("POST", "/appointments", body);

        assertEquals(400, reply.status());
        assertEquals("INVALID_REQUEST", reply.body().at("/error/code").asText());
        assertEquals(List.of(), store.findAll());
    }

    @Test
    void unexpectedErrorReturns500WithoutLeakingDetails() throws Exception {
        startServer(new InMemoryAppointmentStore() {
            @Override
            public synchronized List<Appointment> findByProvider(String providerId) {
                throw new IllegalStateException("simulated defect");
            }
        });

        Reply reply = book();

        assertEquals(500, reply.status());
        assertEquals("INTERNAL_ERROR", reply.body().at("/error/code").asText());
        assertFalse(reply.body().toString().contains("simulated defect"));
    }

    @Test
    void cancelLifecycle() throws Exception {
        startServer(new InMemoryAppointmentStore());
        String id = book().body().get("appointmentId").asText();
        String cancelPath = "/appointments/" + id + "/cancel";

        Reply cancelled = send("POST", cancelPath, null);
        assertEquals(200, cancelled.status());
        assertEquals("CANCELLED", cancelled.body().get("status").asText());

        Reply again = send("POST", cancelPath, null);
        assertEquals(409, again.status());
        assertEquals("APPOINTMENT_ALREADY_CANCELLED", again.body().at("/error/code").asText());

        Reply fetched = send("GET", "/appointments/" + id, null);
        assertEquals(200, fetched.status());
        assertEquals("CANCELLED", fetched.body().get("status").asText());

        // Slot is free again.
        assertEquals(201, book().status());
    }

    @Test
    void cancelUnknownReturns404() throws Exception {
        startServer(new InMemoryAppointmentStore());
        Reply reply = send("POST", "/appointments/nope/cancel", null);
        assertEquals(404, reply.status());
        assertEquals("APPOINTMENT_NOT_FOUND", reply.body().at("/error/code").asText());
    }

    @Test
    void getUnknownReturns404() throws Exception {
        startServer(new InMemoryAppointmentStore());
        Reply reply = send("GET", "/appointments/nope", null);
        assertEquals(404, reply.status());
        assertEquals("APPOINTMENT_NOT_FOUND", reply.body().at("/error/code").asText());
    }

    @Test
    void unknownRouteReturns404() throws Exception {
        startServer(new InMemoryAppointmentStore());
        Reply reply = send("POST", "/providers", "{}");
        assertEquals(404, reply.status());
        assertEquals("NOT_FOUND", reply.body().at("/error/code").asText());
    }

    @Test
    void simultaneousOverlappingHttpBookingsExactlyOneSucceeds() throws Exception {
        startServer(new SlowAppointmentStore());
        List<Callable<Integer>> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            requests.add(() -> book().status());
        }

        List<Integer> statuses = Concurrently.run(requests).stream()
                .map(outcome -> {
                    assertTrue(outcome.ok(), () -> "request failed: " + outcome.error());
                    return outcome.value();
                })
                .sorted()
                .toList();

        assertEquals(List.of(201, 409, 409, 409, 409, 409, 409, 409, 409, 409), statuses);
        assertEquals(1, store.findAll().size());
    }
}
