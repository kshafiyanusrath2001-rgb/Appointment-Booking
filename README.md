# Appointment Booking Service

A small Java service that books and cancels provider appointments and
guarantees that **a provider never has two overlapping ACTIVE appointments**,
including under concurrent requests.

- Design: [DESIGN.md](docs/DESIGN.md)
- Assumptions and limitations: [ASSUMPTIONS_AND_LIMITATIONS.md](docs/ASSUMPTIONS_AND_LIMITATIONS.md)
- Conflict detection review: [REVIEW.md](docs/REVIEW.md)
- AI usage record: [AI_USAGE.md](docs/AI_USAGE.md)

## Prerequisites

- JDK 17 or newer
- Network access on the first build (the Maven Wrapper downloads Maven, dependencies and plugins)

The checked-in Maven Wrapper pins Maven 3.9.15, so a separate Maven installation is not required.
Check Java:

```powershell
java -version
```

On Windows, run `.\mvnw.cmd` from the project directory. On macOS/Linux, run
`sh ./mvnw`.

This project exposes a JSON HTTP API, not a browser UI. Opening
`http://127.0.0.1:8080/` returns `NOT_FOUND`; use the API requests below to
book, fetch, or cancel appointments.

## Build

```bash
./mvnw -B -ntp clean verify
```

Windows PowerShell:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

`clean verify` compiles the project, runs all tests, and packages the JAR.

## Run the tests

All tests (unit, integration and concurrency):

```bash
sh ./mvnw -B -ntp test
```

Windows PowerShell:

```powershell
.\mvnw.cmd -B -ntp test
```

A single test class:

```bash
sh ./mvnw -B -ntp -Dtest=AppointmentConcurrencyTest test
```

| Test class | Covers |
|---|---|
| `AppointmentTest` | Domain invariants: non-blank identifiers and positive-duration appointments |
| `InMemoryAppointmentStoreTest` | Prevents provider changes from corrupting the provider index |
| `UtcTimestampsTest` | Accepted/rejected timestamp formats, UTC normalisation, range limits |
| `AppointmentServiceTest` | Booking, overlap boundaries, validation, cancellation, no partial state changes |
| `AppointmentConcurrencyTest` | Simultaneous overlapping bookings, double cancellation, cancel-vs-rebook race, randomised stress test |
| `AppointmentHttpServerTest` | Real HTTP requests: status codes, error bodies, concurrent HTTP bookings |

## Run the service

```bash
sh ./mvnw -q compile exec:java
```

Windows PowerShell:

```powershell
.\mvnw.cmd -q compile exec:java
```

Listens on `http://127.0.0.1:8080`. To change the port:

```bash
sh ./mvnw -q compile exec:java -Dexec.args="--port 9090"
```

Stop with Ctrl+C.

## Sample requests

Book:

```bash
curl -s -X POST http://127.0.0.1:8080/appointments -H 'Content-Type: application/json' -d '{"providerId":"dr-smith","startTime":"2026-01-15T10:00:00Z","endTime":"2026-01-15T11:00:00Z"}'
```

```json
{"appointmentId":"6f1c…","providerId":"dr-smith","startTime":"2026-01-15T10:00:00Z","endTime":"2026-01-15T11:00:00Z","status":"ACTIVE"}
```

Book an overlapping slot for the same provider. This returns **409 Conflict**:

```bash
curl -s -X POST http://127.0.0.1:8080/appointments -H 'Content-Type: application/json' -d '{"providerId":"dr-smith","startTime":"2026-01-15T10:30:00Z","endTime":"2026-01-15T11:30:00Z"}'
```

```json
{"error":{"code":"BOOKING_CONFLICT","message":"Provider already has an active appointment overlapping the requested time","conflictingAppointmentId":"6f1c…"}}
```

Book the adjacent slot. This **succeeds** (201), because 11:00 is the end of the first appointment and intervals are half-open:

```bash
curl -s -X POST http://127.0.0.1:8080/appointments -H 'Content-Type: application/json' -d '{"providerId":"dr-smith","startTime":"2026-01-15T11:00:00Z","endTime":"2026-01-15T12:00:00Z"}'
```

Cancel (replace the ID):

```bash
curl -s -X POST http://127.0.0.1:8080/appointments/<appointmentId>/cancel
```

Cancelling again returns **409** with `APPOINTMENT_ALREADY_CANCELLED`, and an unknown ID returns **404** with `APPOINTMENT_NOT_FOUND`.

Fetch:

```bash
curl -s http://127.0.0.1:8080/appointments/<appointmentId>
```

Invalid input returns **400** naming the field, for example a timestamp without an offset:

```json
{"error":{"code":"VALIDATION_ERROR","message":"startTime must be an ISO 8601 timestamp with an explicit UTC offset, e.g. 2026-01-15T09:00:00Z","field":"startTime"}}
```

## Expected behaviour for conflicting bookings

- Same provider, overlapping `[start, end)` with an ACTIVE appointment → `409 BOOKING_CONFLICT`; nothing is stored.
- Different provider, same times → allowed.
- Touching intervals (one ends exactly when the other starts) → allowed.
- Overlapping a **cancelled** appointment → allowed.
- Times in different offsets are compared as instants: `16:00+05:30` conflicts with `10:00Z–11:00Z`.
- Many simultaneous overlapping requests for one provider → exactly one `201`, all others `409`.

## Project layout

```
pom.xml
src/main/java/com/example/appointments/
    Appointment.java, AppointmentStatus.java       model + overlap predicate
    UtcTimestamps.java                              strict UTC timestamp parsing
    AppointmentService.java                         business rules + locking
    InMemoryAppointmentStore.java                   persistence
    *Exception.java                                 domain errors
    http/AppointmentHttpServer.java                 JSON/HTTP adapter + main()
src/test/java/com/example/appointments/
    AppointmentTest, InMemoryAppointmentStoreTest
    UtcTimestampsTest, AppointmentServiceTest, AppointmentConcurrencyTest
    SlowAppointmentStore, Concurrently              concurrency test helpers
    http/AppointmentHttpServerTest
```
