# Design: Conflict-Safe Appointment Booking and Cancellation

## 1. Overview and scope

A small Java service that books and cancels appointments for providers. Its main
guarantee:

> **For a given provider, no two ACTIVE appointments may overlap.**

There was no starter project (the repository was empty), so this is a minimal
backend with an in-memory store, as the brief allows.

**In scope:** appointment model, booking validation, provider-specific conflict
detection, creation, cancellation, thread-safe persistence, a thin HTTP adapter
and automated tests.

**Out of scope (deliberately):** UI, authentication/authorisation,
notifications, recurring availability, calendar integration, deployment, a
real database.

## 2. Service boundary and responsibilities

```
HTTP client
    │  JSON
    ▼
AppointmentHttpServer      (http/)   routing, JSON parsing, JSON type checks, error → status mapping
    │  Java method calls
    ▼
AppointmentService                   validation, booking invariant, cancellation rules, LOCKING
    │
    ▼
InMemoryAppointmentStore             holds records; no business rules
```

| Class | Responsibility |
|---|---|
| `Appointment` (record) | Immutable data, constructor-enforced field/interval invariants, and the half-open overlap predicate |
| `AppointmentStatus` | `ACTIVE`, `CANCELLED` |
| `UtcTimestamps` | Strict timestamp parsing → UTC `Instant`; formatting |
| `AppointmentService` | The only place business rules live and where the booking lock is taken |
| `InMemoryAppointmentStore` | Map by ID + index by provider; synchronized methods share the monitor used by the service; replacement cannot change provider |
| `*Exception` | Domain errors, each with a stable machine-readable `code()` |
| `http.AppointmentHttpServer` | JSON-over-HTTP adapter around the service (JDK `com.sun.net.httpserver`) |

Booking and cancellation rules live in the service, so the HTTP layer and
service tests use one shared implementation of conflict detection. The model
and store also enforce basic record and index integrity at their boundaries.

## 3. Data model

```java
public record Appointment(
        String appointmentId,      // server-generated UUID
        String providerId,         // opaque, case-sensitive, exact match
        Instant startTime,         // UTC, inclusive
        Instant endTime,           // UTC, exclusive, strictly after startTime
        AppointmentStatus status)  // ACTIVE | CANCELLED
```

Records are immutable. Cancelling creates a new instance (`withStatus`) and
replaces the stored one, so a reader never sees a half-updated record.
The compact constructor rejects null or blank identifiers, null fields, and
end times that are not strictly after start times. `InMemoryAppointmentStore`
also rejects a replacement that changes the provider ID, preserving its
provider index.
Cancelled appointments are **kept**, not deleted, to preserve history and
allow clear "already cancelled" responses.

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

The service accepts timestamps as **strings** on purpose, so parsing and
validation live in one place no matter which adapter calls it.

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

**Why this is correct.** Two half-open intervals are *disjoint* exactly when
one ends at or before the other starts: `E <= s` (existing is entirely before)
or `e <= S` (existing is entirely after). Negating that disjunction (De
Morgan) gives `E > s AND e > S`, which is the condition above. It covers
every overlap shape with no special cases: partial overlap at either end,
containment in either direction, and identical intervals. Because the
comparisons are strict, an appointment ending at 10:00 and one starting at
10:00 do **not** overlap.

Consequences, each covered by tests:

- Different providers never conflict (lookup is by provider).
- Adjacent appointments (`E == s`) are allowed.
- Cancelled appointments are skipped (`existing.isActive()`).
- Zero/negative durations are rejected before the check, so every stored
  interval is non-empty and the formula's assumptions hold.

The check scans the provider's appointments, O(n) per provider. That's
adequate for a prototype; see §10.

## 6. Timestamp handling (UTC)

Accepted format (an RFC 3339 / ISO 8601 subset):

```
YYYY-MM-DDTHH:MM:SS[.fraction 1–9 digits](Z | +HH:MM | -HH:MM)
```

- An offset is **mandatory**: a timestamp without one is ambiguous local time and is rejected.
- Valid values are normalised to a UTC `Instant` immediately, so all
  comparisons and storage use one timeline. `2026-01-15T15:30:00+05:30` and
  `2026-01-15T10:00:00Z` are the same instant.
- A strict regex enforces the *shape* first (rejecting date-only, space separator,
  missing seconds, `+0530`, offsets with seconds, lowercase `z`, surrounding
  whitespace, non-ASCII digits). Then `DateTimeFormatter.ISO_OFFSET_DATE_TIME`
  (strict resolver) rejects impossible *values* (month 13, 30 Feb, 29 Feb in a
  non-leap year, hour 24, second 60, offset beyond ±18:00).
- Normalised instants must fall within years 0000–9999 UTC, so every stored
  value can be formatted and parsed back with the same format.
- Output is always UTC with `Z` (`DateTimeFormatter.ISO_INSTANT`).
- No timezone database, DST or local-time logic is involved: offsets are explicit.

## 7. Validation rules

Service validation happens **before** the lock is taken or any state is
touched, so an invalid request can't cause a partial change. The
`Appointment` constructor independently enforces non-blank IDs and positive
duration, including for direct construction outside the service.

| Field | Rule | Error |
|---|---|---|
| `providerId` | non-null, not blank | `providerId is required` |
| `startTime` / `endTime` | non-null, non-empty | `… is required` |
| `startTime` / `endTime` | format in §6 | `… must be an ISO 8601 timestamp with an explicit UTC offset…` / `… is not a valid date/time` / `… is outside the supported date range` |
| `endTime` | strictly after `startTime` (compared as instants) | `endTime must be after startTime` |
| `appointmentId` (cancel/get) | non-null, not blank | `appointmentId is required` |
| any JSON field (HTTP only) | must be a JSON string if present | `… must be a string` |

The first failing rule is reported. Order: JSON type checks (HTTP), then
`providerId`, `startTime`, `endTime`, then duration.

## 8. Cancellation

- Only an existing `ACTIVE` appointment can be cancelled; its status becomes `CANCELLED`.
- A cancelled appointment no longer blocks its slot.
- Unknown ID → `AppointmentNotFoundException` (HTTP 404).
- Already cancelled → `AppointmentAlreadyCancelledException` (HTTP 409).
  Repeat cancellation is **not** treated as success. There's no existing
  convention requiring idempotency, and silently succeeding would hide client
  bugs, such as cancelling the wrong ID.
- Cancellation never touches other appointments.

## 9. Persistence and concurrency

### The race

Booking is *check-then-act*: (1) read the provider's appointments and check for
overlap, (2) insert. If two overlapping requests run concurrently, both can
complete step 1 before either completes step 2. Both see no conflict, and both
insert. Cancellation has the same shape: two concurrent cancels can both see
`ACTIVE` and both report success.

Making each store operation thread-safe (the store's `synchronized` methods)
does **not** fix this. The problem is the gap *between* the two operations. In a
database, an ordinary `READ COMMITTED` transaction doesn't fix it either: both
transactions can read "no overlapping rows" and both insert, because neither
row exists yet to be locked (a phantom).

### Chosen approach: one lock per store

`AppointmentService` holds the **store instance's monitor** (`synchronized (store)`)
around these sequences:

- booking: *find provider's appointments → check overlap → insert*
- cancellation: *find by ID → check status → replace*

Validation, which is pure, runs outside the lock to keep the critical section
small.

The store's public `add` and `replace` methods are low-level operations, not
booking APIs. Calling them directly can bypass the service's conflict check;
the invariant is guaranteed for writes routed through `AppointmentService`.
The store does guard its provider index against provider changes on replacement.

**Why the store's monitor and not a private lock field?** The invariant is a
property of the stored data, so the lock is scoped to the data. An earlier
version used a private `lock` field in the service. The code review
(REVIEW.md) showed that two services sharing one store would then hold different
locks and could double-book. The store's own methods are `synchronized` on the
same monitor, and Java monitors are reentrant, so they can be called while it is held. The HTTP server uses a thread pool (16 threads), so requests really do
execute in parallel and contend on this lock.

**Why one lock for all providers rather than per-provider locks?** It's obviously correct,
has no lock-lifecycle or map-of-locks bugs, and also serialises cancellation
against booking without the cancel path having to look up the provider first.
The cost is that bookings for *different* providers are serialised too. For an
in-memory prototype the critical section is microseconds, so that's an
acceptable trade. Per-provider locking is the natural next step if contention
shows up.

**Cancel vs. book on the same slot.** Both take the same lock, so they're
ordered. If the cancel runs first, the booking succeeds. If the booking runs
first, it's rejected as a conflict. Either outcome is valid. This race can't
break the invariant even in principle: cancelling only removes active
appointments, so a booking that reads stale data is merely more conservative.
The lock matters here for *double cancellation*, where two cancels must not both succeed.

### How this maps to a real database (not implemented)

| Option | Notes |
|---|---|
| **PostgreSQL exclusion constraint**: `EXCLUDE USING gist (provider_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&) WHERE (status = 'ACTIVE')` | Preferred: the database enforces the invariant itself, even against buggy code paths. The `'[)'` range matches our half-open convention. |
| Lock the provider row (`SELECT … FROM providers WHERE id = ? FOR UPDATE`) before check + insert | Per-provider serialisation; needs a providers table. |
| `SERIALIZABLE` isolation | Correct, but callers must retry on serialisation failures. |

## 10. Key decisions and tradeoffs

| Decision | Alternatives considered | Why |
|---|---|---|
| Plain Java 17 + JDK HTTP server + Jackson | Spring Boot | Much smaller surface for a single feature; Jackson only because the JDK has no JSON parser (a hand-written parser would be a correctness risk) |
| Existing in-memory store | Embedded DB (H2/SQLite) | Preserves the starter's persistence approach and avoids new infrastructure |
| One lock (the store's monitor) for all providers | Per-provider locks, lock-free CAS, private lock field in the service | Simplest provably-correct option; scoped to the data so shared stores stay safe; contention negligible at this scale |
| Half-open intervals | Closed intervals | Back-to-back bookings are the norm; half-open makes adjacency non-overlapping with no special cases |
| Mandatory explicit offset | Assume UTC when missing | Silent assumptions about local time cause double bookings across time zones |
| Re-cancel is an error (409) | Idempotent success | Brief asks for a clear result; no existing convention to the contrary |
| Linear scan per provider | Interval tree / sorted map | O(n) is fine for a prototype; noted as a scaling limitation |
| Service accepts timestamp strings | Accept `Instant` | Keeps all parsing/validation in one place for every caller |
