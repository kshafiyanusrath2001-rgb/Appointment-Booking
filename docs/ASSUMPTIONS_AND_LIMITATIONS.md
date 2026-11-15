# Behavior Assumptions and Known Limits

## Rules this implementation assumes

### Time input and UTC storage
- Inputs need a `Z` or numeric `±HH:MM` offset. Local timestamps without an
  offset are rejected instead of being interpreted using the machine's time zone.
- Accepted inputs are converted to UTC `Instant` values before they are stored
  or compared; responses use a `Z` suffix.
- The accepted shape is `YYYY-MM-DDTHH:MM:SS[.f{1,9}](Z|±HH:MM)`. Date-only
  strings, week dates, `+0530`, offset seconds, and lowercase `z` are not accepted.
- Nanosecond precision is supported, including a positive duration of one nanosecond.
- After normalization, timestamps must fall in UTC years 0000–9999.
- Leap seconds (`:60`) are outside the `java.time` representation used here and are rejected.

### Interval boundaries
- A booking occupies `[startTime, endTime)`: the start is included and the end is not.
- Two bookings may meet at an endpoint without overlapping.
- Every interval must have a positive duration.

### Appointment lifecycle
- `ACTIVE` records reserve their times and prevent same-provider overlaps.
- `CANCELLED` records remain available for lookup but no longer reserve a slot.
- The supported state change is `ACTIVE → CANCELLED`; restore and reschedule operations are not provided.

### Identifiers
- The service creates appointment IDs as random UUIDs; clients do not supply them when booking.
- `Appointment` rejects blank IDs, blank provider IDs, and non-positive intervals.
- Provider IDs are opaque and case-sensitive. For example, `"p1"`, `"P1"`, and
  `"p1 "` are distinct values. There is no provider registry; any non-blank ID is accepted.
- HTTP identifier and timestamp fields must be JSON strings.

### Cancellation responses
- Only a stored `ACTIVE` appointment can transition to `CANCELLED`.
- An unknown ID produces “not found” (HTTP 404).
- A repeated cancellation produces “already cancelled” (HTTP 409), rather than a successful no-op.

### Concurrency boundary
- The guarantee applies within one process/JVM. Booking and cancellation use the
  store instance's monitor, including when multiple service objects share that store.
- For concurrent overlapping requests to the same provider, one can be accepted;
  the rest receive `BOOKING_CONFLICT`. The implementation does not promise which
  request wins or provide FIFO fairness.
- If a new booking races cancellation of its blocker, either operation may acquire
  the monitor first. The resulting booking may therefore succeed or conflict; both
  outcomes preserve the rule.

## Current limitations

- **Volatile storage:** all records are in memory and disappear when the process exits.
- **One JVM only:** the monitor cannot coordinate another process or host. A
  multi-instance deployment would need database-enforced conflict protection
  (see [DESIGN.md](DESIGN.md), §9).
- **One lock for all providers:** independent providers still contend for the same monitor.
- **Linear lookup:** conflict checks scan a provider's records, including cancelled ones.
- **Unbounded HTTP body and no rate limit:** request size and request frequency are not capped.
- **Exact provider IDs:** inconsistent capitalization or whitespace can make one
  real provider appear as multiple IDs. No canonicalization was specified, so
  this behavior is documented rather than silently changed (review risk R1).
- **No authentication or authorization:** a caller who knows an ID can cancel
  that appointment; conflict errors include the conflicting appointment ID.
- **No business scheduling policy:** past appointments, long durations, and
  times outside business hours are not restricted.
- **No collection endpoint:** appointments can be fetched by ID, but not listed or searched.
- **Single validation error:** a request reports its first invalid field, not every issue at once.

## Risks

| Risk | Impact | Mitigation in place / future |
|---|---|---|
| Code writes through the store instead of the service | It can bypass overlap checking | The service owns the compound operation; model and replacement checks protect basic record/index integrity only |
| A future database implementation uses an ordinary check-then-insert transaction | Concurrent requests may both insert | Move the invariant into a database constraint or use suitable locking/isolation (see DESIGN.md) |
| A client submits a local timestamp without an offset | Request is rejected with HTTP 400 | The error indicates the required timestamp form |
| Concurrency tests rely on scheduling timing | A test could miss a race in an unlucky run | `SlowAppointmentStore` widens the race window; mutation testing has not been performed |

## Potential next steps (not implemented)

- Database persistence with a PostgreSQL `EXCLUDE USING gist` constraint on `(provider_id, tstzrange(start, end, '[)'))` for active rows.
- Per-provider locking (or DB-level locking) to remove cross-provider contention.
- A sorted per-provider index (e.g. `TreeMap` by start time) of active appointments for O(log n) conflict checks.
- Idempotency keys on booking requests so client retries don't create duplicates.
- Request body size limits, structured logging, metrics.
