package com.example.appointments.http;

import com.example.appointments.Appointment;
import com.example.appointments.AppointmentAlreadyCancelledException;
import com.example.appointments.AppointmentException;
import com.example.appointments.AppointmentNotFoundException;
import com.example.appointments.AppointmentService;
import com.example.appointments.BookingConflictException;
import com.example.appointments.UtcTimestamps;
import com.example.appointments.ValidationException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal JSON-over-HTTP adapter for {@link AppointmentService}.
 *
 * <pre>
 * POST /appointments                 book    -> 201 | 400 | 409
 * POST /appointments/{id}/cancel     cancel  -> 200 | 404 | 409
 * GET  /appointments/{id}            fetch   -> 200 | 404
 * </pre>
 */
public final class AppointmentHttpServer {

    private static final Logger LOG = Logger.getLogger(AppointmentHttpServer.class.getName());
    private static final Pattern APPOINTMENT_PATH = Pattern.compile("/appointments/([^/]+)");
    private static final Pattern CANCEL_PATH = Pattern.compile("/appointments/([^/]+)/cancel");
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final AppointmentService service;
    private final HttpServer server;
    private final ExecutorService executor;

    public AppointmentHttpServer(AppointmentService service, String host, int port, int threads)
            throws IOException {
        this.service = service;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        // The default HttpServer executor is single-threaded, which would hide
        // concurrency issues; use a pool so requests really run in parallel.
        this.executor = Executors.newFixedThreadPool(threads);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            Response response;
            try {
                response = route(exchange);
            } catch (BadRequestException e) {
                response = error(400, "INVALID_REQUEST", e.getMessage());
            } catch (AppointmentException e) {
                response = new Response(statusFor(e), errorBody(e));
            } catch (RuntimeException e) {
                // Always answer the client; details go to the server log only.
                LOG.log(Level.SEVERE, "Unhandled error", e);
                response = error(500, "INTERNAL_ERROR", "Internal server error");
            }
            send(exchange, response);
        } finally {
            exchange.close();
        }
    }

    private Response route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if ("POST".equals(method) && "/appointments".equals(path)) {
            JsonNode body = readJsonObject(exchange);
            Appointment booked = service.bookAppointment(
                    stringField(body, "providerId"),
                    stringField(body, "startTime"),
                    stringField(body, "endTime"));
            return new Response(201, toJson(booked));
        }
        Matcher cancel = CANCEL_PATH.matcher(path);
        if ("POST".equals(method) && cancel.matches()) {
            return new Response(200, toJson(service.cancelAppointment(cancel.group(1))));
        }
        Matcher fetch = APPOINTMENT_PATH.matcher(path);
        if ("GET".equals(method) && fetch.matches()) {
            return new Response(200, toJson(service.getAppointment(fetch.group(1))));
        }
        return error(404, "NOT_FOUND", "Unknown route");
    }

    private static JsonNode readJsonObject(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        JsonNode body;
        try {
            body = raw.length == 0 ? null : JSON.readTree(raw);
        } catch (IOException e) {
            throw new BadRequestException("Request body must be valid JSON");
        }
        if (body == null || !body.isObject()) {
            throw new BadRequestException("Request body must be a JSON object");
        }
        return body;
    }

    /** Returns the field's string value, null if absent/null, or rejects non-strings. */
    private static String stringField(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new ValidationException(field, "must be a string");
        }
        return node.asText();
    }

    static Map<String, Object> toJson(Appointment appointment) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("appointmentId", appointment.appointmentId());
        json.put("providerId", appointment.providerId());
        json.put("startTime", UtcTimestamps.format(appointment.startTime()));
        json.put("endTime", UtcTimestamps.format(appointment.endTime()));
        json.put("status", appointment.status().name());
        return json;
    }

    private static int statusFor(AppointmentException e) {
        if (e instanceof ValidationException) {
            return 400;
        }
        if (e instanceof AppointmentNotFoundException) {
            return 404;
        }
        if (e instanceof BookingConflictException || e instanceof AppointmentAlreadyCancelledException) {
            return 409;
        }
        return 400;
    }

    private static Map<String, Object> errorBody(AppointmentException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", e.code());
        error.put("message", e.getMessage());
        if (e instanceof ValidationException validation) {
            error.put("field", validation.field());
        }
        if (e instanceof BookingConflictException conflict) {
            error.put("conflictingAppointmentId", conflict.conflictingAppointmentId());
        }
        return Map.of("error", error);
    }

    private static Response error(int status, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        return new Response(status, Map.of("error", error));
    }

    private static void send(HttpExchange exchange, Response response) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(response.body());
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private record Response(int status, Object body) {
    }

    private static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }

    /** Usage: {@code [--host 127.0.0.1] [--port 8080]}. */
    public static void main(String[] args) throws IOException {
        String host = "127.0.0.1";
        int port = 8080;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--host" -> host = args[i + 1];
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        AppointmentHttpServer server = new AppointmentHttpServer(new AppointmentService(), host, port, 16);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("Listening on http://" + host + ":" + server.port());
    }
}
