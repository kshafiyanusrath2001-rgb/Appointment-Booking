# Design: Appointment Booking and Cancellation

## 1. Overview and scope

This feature extends the supplied Java 17 Maven application, keeping its
service/store split, JSON HTTP adapter, and in-memory persistence. The behavior
is organized around one rule:

> **For a given provider, no two ACTIVE appointments may overlap.**

The implementation covers interval validation, provider-specific conflict
checks, booking and cancellation, synchronization, the existing HTTP boundary,
and focused tests. It does not add a UI, authentication, notifications,
recurring availability, calendar integration, deployment, or another database.

## 2. Service boundary and responsibilities

```
HTTP client
    │  JSON
    ▼
AppointmentHttpServer      (http/)   routing, JSON parsing/type checks, mapping errors to HTTP responses
    │  Java method calls
    ▼
AppointmentService                   validation, booking invariant, cancellation, synchronization
    │
    ▼
InMemoryAppointmentStore             holds records; no business rules
```

| Class | Responsibility |
|---|---|
| `Appointment` (record) | Immutable appointment data, validated identifiers and interval, and interval-overlap logic |
| `AppointmentStatus` | The two lifecycle values: `ACTIVE` and `CANCELLED` |
| `UtcTimestamps` | Parses the accepted timestamp form to an `Instant` and formats UTC output |
| `AppointmentService` | Applies validation, booking/cancellation rules, and the shared-store lock |
| `InMemoryAppointmentStore` | Stores records by ID and indexes their IDs by provider; replacement cannot change a provider |
| `*Exception` | Domain failures with stable machine-readable error codes |
| `http.AppointmentHttpServer` | Adapts the service to JSON and the JDK HTTP server |

The service owns the scheduling rules, so both direct Java calls and HTTP
requests follow the same conflict and cancellation behavior. The record
constructor and store add basic validation at their own boundaries.

## 3. Data model

```java
public record Appointment(
        String appointmentId,      // server-generated UUID
        String providerId,         // opaque, case-sensitive, exact match
        Instant startTime,         // UTC, inclusive
        Instant endTime,           // UTC, exclusive, strictly after startTime
        AppointmentStatus status)  // ACTIVE | CANCELLED
```

An appointment cannot be mutated in place. Cancellation makes a copy with the
new status and replaces the stored record. Construction rejects null fields,
blank identifiers, and intervals whose end is not later than the start. The
store also refuses to replace a record under a different provider, which
would otherwise invalidate its provider index.

Cancelled records remain in the store. Retaining them preserves the result of
a later lookup and lets the service distinguish an unknown ID from a repeated
cancellation.

## 4. API contracts

### Java (service)

```java
Appointment bookAppointment(String providerId, String startTime, String endTime)
    // throws ValidationException, BookingConflictException
Appointment cancelAppointment(String appointmentId)
    // throws ValidationException, AppointmentNotFoundException, AppointmentAlreadyCancelledException
Appointment getAppointment(String appointmentId)
    // throws ValidationException, AppointmentNotFoundException
```

The service takes timestamp strings so that every caller uses the same parser
and validation rules.

### HTTP

| Method & path | Success | Errors |
|---|---|---|
| `POST /appointments` body `{"providerId","startTime","endTime"}` | `201` appointment | `400` validation / bad JSON, `409` conflict |
| `POST /appointments/{id}/cancel` | `200` appointment (status `CANCELLED`) | `404` not found, `409` already cancelled |
| `GET /appointments/{id}` | `200` appointment | `404` not found |

Appointment JSON: `{"appointmentId","providerId","startTime","endTime","status"}`; timestamps are always returned in UTC with `Z`.

Error JSON: `{"error":{"code","message", "field"?, "conflictingAppointmentId"?}}`.

| `code` | HTTP | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Missing/invalid field; `field` names it |
| `INVALID_REQUEST` | 400 | Body is not a valid JSON object |
| `BOOKING_CONFLICT` | 409 | Overlaps an active appointment; `conflictingAppointmentId` given |
| `APPOINTMENT_NOT_FOUND` | 404 | Unknown appointment ID |
| `APPOINTMENT_ALREADY_CANCELLED` | 409 | Cancel requested on a cancelled appointment |
| `NOT_FOUND` | 404 | Unknown route |
| `INTERNAL_ERROR` | 500 | Unexpected defect; no internal details leaked |

## 5. Booking invariant and overlap algorithm

Appointments occupy the **half-open interval `[startTime, endTime)`**: the start
instant is included, the end instant is not.

A requested interval `[s, e)` conflicts with an existing appointment `[S, E)` of
the **same provider** whose status is **ACTIVE** if and only if:

```
S < e  AND  s < E
```

The condition is the negation of the two ways intervals can be separate:
`E <= s` (the existing one ends before the request) or `e <= S` (the request
ends before the existing one). Thus, only pairs that intersect are rejected.
This handles partial overlaps, containment, and identical intervals. Strict
comparisons mean an end at 10:00 and a start at 10:00 can touch without
conflicting.

Consequences, each covered by tests:

- Different providers never conflict (lookup is by provider).
- Adjacent appointments (`E == s`) are allowed.
- Cancelled appointments are skipped (`existing.isActive()`).
- Zero/negative durations are rejected before the check, so every stored
  interval is non-empty and the formula's assumptions hold.

Conflict lookup scans that provider's stored appointments, so its work grows
linearly with that provider's booking history. This is acceptable for the
current in-memory service; scaling options are listed in §10.

## 6. Timestamp handling (UTC)

The parser accepts this restricted ISO 8601 timestamp form:

```
YYYY-MM-DDTHH:MM:SS[.fraction 1–9 digits](Z | +HH:MM | -HH:MM)
```

- A zone offset is required; local date-times without one are ambiguous and rejected.
- Accepted inputs are converted to `Instant` before storage or comparison. For
  example, `15:30+05:30` and `10:00Z` identify the same instant.
- A regular expression limits the input syntax. The strict Java time formatter
  then rejects impossible dates/times, leap seconds, and offsets outside the
  supported range.
- The normalized UTC value must remain within years 0000 through 9999.
- Responses use UTC with a `Z` suffix. No time-zone database or daylight-saving
  conversion is involved because each input supplies its own offset.

## 7. Validation rules

The service checks the complete request before entering the critical section
or changing the store. The `Appointment` constructor independently checks
identifiers and interval duration, so direct construction receives the same
basic safeguards.

| Field | Rule | Error |
|---|---|---|
| `providerId` | non-null, not blank | `providerId is required` |
| `startTime` / `endTime` | non-null, non-empty | `… is required` |
| `startTime` / `endTime` | format in §6 | `… must be an ISO 8601 timestamp with an explicit UTC offset…` / `… is not a valid date/time` / `… is outside the supported date range` |
| `endTime` | strictly after `startTime` (compared as instants) | `endTime must be after startTime` |
| `appointmentId` (cancel/get) | non-null, not blank | `appointmentId is required` |
| any JSON field (HTTP only) | must be a JSON string if present | `… must be a string` |

Only the first validation failure is returned. HTTP type checks run first,
followed by provider ID, start time, end time, and duration.

## 8. Cancellation

- Cancellation is valid only for a stored `ACTIVE` appointment; the record is
  retained with status `CANCELLED`.
- Cancelled appointments are excluded from later conflict checks.
- An unknown ID maps to `AppointmentNotFoundException` and HTTP 404.
- A second cancellation maps to `AppointmentAlreadyCancelledException` and
  HTTP 409. It is intentionally not an idempotent success, which would hide
  client mistakes such as using the wrong ID.
- The operation changes no other appointment.

## 9. Persistence and concurrency

### The race

Booking consists of a read/check followed by an insert. Without coordination,
two concurrent requests could each observe an empty slot and then both write.
Cancellation has a similar race: two callers might both read `ACTIVE` and
both claim to have cancelled the appointment.

Synchronizing each individual store method does not protect the sequence as a
whole; the check/write gap remains. Likewise, an ordinary database
`READ COMMITTED` transaction can allow both requests to see no conflicting row
and insert one, since there is not yet a row to lock.

### Synchronization boundary: the store instance

`AppointmentService` synchronizes on the store object while performing each
compound state transition:

- booking: retrieve the provider's appointments, check them, and insert if clear;
- cancellation: look up the record, verify its state, and replace it.

Request validation and appointment-ID generation happen before taking the
monitor because neither operation reads or changes shared store state.

The store's `add` and `replace` methods are persistence operations, not
conflict-safe booking APIs. Calling them outside the service can bypass the
overlap rule. A replacement cannot change the provider, so it cannot corrupt
the provider index.

**Why synchronize on the store?** The invariant belongs to the shared data.
Locking the store means that two `AppointmentService` objects using the same
store coordinate on one monitor. A previous service-owned lock did not provide
that guarantee; the review in [REVIEW.md](REVIEW.md) describes the issue and
its regression tests. Store methods synchronize on the same monitor, which is
reentrant in Java. The HTTP adapter uses a 16-thread pool, so requests can
arrive concurrently and contend on it.

**Why not use a separate lock per provider?** One store monitor is simple and
also protects cancellation without first resolving a provider-specific lock.
The tradeoff is that requests for different providers are serialized too.
Provider-level locks could reduce contention if the prototype grows and that
cost becomes measurable.

**Booking versus cancellation:** the shared monitor establishes an order. If
cancellation happens first, the new booking can proceed; if booking happens
first, it sees the active appointment and conflicts. The invariant also remains
safe under this race because cancellation only removes an active blocker. The
lock is essential for preventing two concurrent cancellations from both
reporting success.

### If persistence moves to a database (not implemented)

| Option | Notes |
|---|---|
| **PostgreSQL exclusion constraint**: `EXCLUDE USING gist (provider_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&) WHERE (status = 'ACTIVE')` | Enforces the same half-open overlap rule in the database, including writes that bypass application code. |
| Lock the provider row (`SELECT … FROM providers WHERE id = ? FOR UPDATE`) before check + insert | Serializes bookings for one provider; requires a provider row to lock. |
| `SERIALIZABLE` isolation | Can protect the invariant, but callers must handle serialization failures and retry safely. |

## 10. Key decisions and tradeoffs

| Decision | Alternatives considered | Why |
|---|---|---|
| Java 17, JDK HTTP server, and Jackson | Spring Boot | Retains a small stack for one service slice; Jackson supplies JSON parsing without a custom parser |
| Existing in-memory store | Embedded DB (H2/SQLite) | Keeps the starter's persistence design and avoids adding infrastructure |
| One store monitor | Per-provider locks, lock-free CAS, service-owned lock | Ties synchronization to shared data and avoids a lock registry |
| Half-open intervals | Closed intervals | Allows back-to-back bookings without special-case overlap rules |
| Require an explicit timestamp offset | Assume a missing offset is UTC | Prevents ambiguous local times from being interpreted differently |
| Re-cancellation returns an error | Treat repeats as successful | Makes a duplicate or mistaken cancellation visible to the caller |
| Scan a provider's records | Interval tree or sorted index | Keeps this small implementation straightforward; larger histories may need an index |
| Service accepts timestamp strings | Accept `Instant` values | Centralizes parsing and validation for all service callers |
