# Provider Appointment Booking

This Java service exposes a small JSON API for booking, retrieving, and
cancelling provider appointments. Its central guarantee is that **one provider
cannot hold two overlapping ACTIVE appointments**, even when requests arrive
at the same time.

- How the service is structured: [DESIGN.md](docs/DESIGN.md)
- Behavior assumptions and known limits: [ASSUMPTIONS_AND_LIMITATIONS.md](docs/ASSUMPTIONS_AND_LIMITATIONS.md)
- Conflict and concurrency review: [REVIEW.md](docs/REVIEW.md)
- AI activity and decisions: [AI_USAGE.md](docs/AI_USAGE.md)

## Prerequisites

- JDK 17 or newer.
- Internet access for the first build, so Maven and required artifacts can be downloaded.

The included Maven Wrapper selects Maven 3.9.15; you do not need to install
Maven system-wide. Confirm Java is available with:

```powershell
java -version
```

On Windows, run `.\mvnw.cmd` from the project directory. On macOS/Linux, run
`sh ./mvnw`.

The application is an HTTP API, not a browser-based product. A request to
`http://127.0.0.1:8080/` has no matching route and returns `NOT_FOUND`.
Use the endpoints below to create, retrieve, or cancel a booking.

## Build

```bash
sh ./mvnw -B -ntp clean verify
```

Windows PowerShell:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

The `clean verify` lifecycle removes old build output, compiles the code,
executes the tests, and creates the JAR.

## Run the tests

Run the complete unit, HTTP integration, and concurrency test suite with:

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
| `AppointmentTest` | Appointment field and positive-duration checks |
| `InMemoryAppointmentStoreTest` | Protects the provider index when a record is replaced |
| `UtcTimestampsTest` | Timestamp syntax, normalization, and supported date bounds |
| `AppointmentServiceTest` | Booking rules, interval edges, validation, cancellation, and unchanged state on rejection |
| `AppointmentConcurrencyTest` | Concurrent bookings and cancellations, plus a stress run |
| `AppointmentHttpServerTest` | Live HTTP behavior, response codes, errors, and parallel booking requests |

## Run the service

```bash
sh ./mvnw -q compile exec:java
```

Windows PowerShell:

```powershell
.\mvnw.cmd -q compile exec:java
```

By default, the server binds to `127.0.0.1:8080`. To select another port:

```bash
sh ./mvnw -q compile exec:java -Dexec.args="--port 9090"
```

Press Ctrl+C to stop the server.

## Sample requests

Book:

```bash
curl -s -X POST http://127.0.0.1:8080/appointments -H 'Content-Type: application/json' -d '{"providerId":"dr-smith","startTime":"2026-01-15T10:00:00Z","endTime":"2026-01-15T11:00:00Z"}'
```

```json
{"appointmentId":"6f1c…","providerId":"dr-smith","startTime":"2026-01-15T10:00:00Z","endTime":"2026-01-15T11:00:00Z","status":"ACTIVE"}
```

Submitting an overlapping interval for that provider returns **409 Conflict**:

```bash
curl -s -X POST http://127.0.0.1:8080/appointments -H 'Content-Type: application/json' -d '{"providerId":"dr-smith","startTime":"2026-01-15T10:30:00Z","endTime":"2026-01-15T11:30:00Z"}'
```

```json
{"error":{"code":"BOOKING_CONFLICT","message":"Provider already has an active appointment overlapping the requested time","conflictingAppointmentId":"6f1c…"}}
```

The next request starts exactly when the first appointment ends. It succeeds
with **201 Created** because the service treats intervals as half-open:

```bash
curl -s -X POST http://127.0.0.1:8080/appointments -H 'Content-Type: application/json' -d '{"providerId":"dr-smith","startTime":"2026-01-15T11:00:00Z","endTime":"2026-01-15T12:00:00Z"}'
```

Cancel a booking by substituting its ID in the path:

```bash
curl -s -X POST http://127.0.0.1:8080/appointments/<appointmentId>/cancel
```

A repeat cancellation returns **409 `APPOINTMENT_ALREADY_CANCELLED`**. An
unknown ID returns **404 `APPOINTMENT_NOT_FOUND`**.

Fetch:

```bash
curl -s http://127.0.0.1:8080/appointments/<appointmentId>
```

Invalid input returns **400** and identifies the field. For example, a
timestamp without an explicit offset is rejected:

```json
{"error":{"code":"VALIDATION_ERROR","message":"startTime must be an ISO 8601 timestamp with an explicit UTC offset, e.g. 2026-01-15T09:00:00Z","field":"startTime"}}
```

## Booking outcomes

- An overlap with an ACTIVE appointment for the same provider returns `409 BOOKING_CONFLICT` and does not add a record.
- The same time may be booked for another provider.
- Back-to-back intervals are accepted; sharing an endpoint is not an overlap.
- A cancelled appointment no longer reserves its interval.
- Offsets are normalized before comparison. For example, `16:00+05:30` overlaps `10:00Z–11:00Z`.
- In a simultaneous set of conflicting booking requests, one succeeds and the others receive `409`.

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
