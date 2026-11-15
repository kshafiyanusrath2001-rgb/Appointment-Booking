# Review: Booking Conflict Detection

**Scope:** overlap boundaries, adjacency, cancelled exclusion, provider isolation,
partial state changes, concurrent bypass of conflict detection.
**Method:** expected outcomes were derived from the specification first, then
compared against the code. This analysis is AI-assisted. The candidate has
confirmed separately that they completed an independent review; details of
that review are not included here. Original verification status is historical;
the follow-up verification addendum supersedes it for the current workspace.

## 1. Edge cases and expected outcomes (derived from the spec, before reading code)

Existing appointment: provider `p1`, `[10:00, 11:00)` UTC, ACTIVE.

| # | Scenario | Expected | Implementation | Test |
|---|---|---|---|---|
| 1 | Identical `[10:00,11:00)` | Reject | ✅ Reject | `overlapBoundariesAgainstExisting10To11` |
| 2 | Inside `[10:15,10:45)` | Reject | ✅ | same |
| 3 | Containing `[09:00,12:00)` | Reject | ✅ | same |
| 4 | Partial `[09:30,10:30)`, `[10:30,11:30)` | Reject | ✅ | same |
| 5 | Same start `[10:00,10:30)`; same end `[10:30,11:00)` | Reject | ✅ | same |
| 6 | Adjacent `[09:00,10:00)`, `[11:00,12:00)` | **Allow** | ✅ | same + `allowsAdjacentAppointmentsOnBothSides` |
| 7 | 1 ns overlap on either side | Reject | ✅ | same |
| 8 | `[16:00+05:30,17:00+05:30)` (= 10:30Z–11:30Z) | Reject | ✅ | `conflictDetectionComparesInstantsNotStrings` |
| 9 | `[16:30+05:30,…)` (= 11:00Z, adjacent) | Allow | ✅ | same |
| 10 | Overlaps only a cancelled appointment | Allow | ✅ | `cancelledAppointmentNoLongerBlocksSlot` |
| 11 | Overlaps a cancelled **and** an active one | Reject, naming the active one | ✅ | same (asserts `conflictingAppointmentId`) |
| 12 | Different provider, same slot | Allow | ✅ | `allowsOverlappingTimesForDifferentProviders` |
| 13 | Invalid input or conflict | Reject, store unchanged | ✅ | `invalidRequestsDoNotModifyStoredData` |
| 14 | Zero-length `[10:00,10:00)` | Reject | ✅ (validation runs first; the overlap formula alone would accept it, since `10:00 < 10:00` is false) | `rejectsZeroDuration` |
| 15 | 10 simultaneous overlapping bookings | Exactly 1 succeeds | ✅ | `simultaneousOverlappingBookingsExactlyOneSucceeds` |
| 16 | Simultaneous double cancel | Exactly 1 succeeds | ✅ | `simultaneousCancellationsExactlyOneSucceeds` |


**Why the overlap formula is right:** `[S,E)` and `[s,e)` are disjoint exactly when
`E <= s` or `e <= S`. Negating gives `S < e && s < E`, which is
`Appointment.java:45`, called as (existing, request). Strict comparisons make
touching intervals non-overlapping.

## 2. Confirmed defect

### D1: Booking lock was scoped to the service object, not the data

- **Where:** `AppointmentService` had `private final Object lock = new Object();` and used `synchronized (lock)`.
- **Why it fails:** the invariant protects data in `InMemoryAppointmentStore`, but each
  `AppointmentService` instance had its own lock. The public constructor
  `AppointmentService(InMemoryAppointmentStore)` allows two services to share one
  store, as a DI container might do with two service instances and a singleton
  repository. A thread in service 1 and a thread in service 2 then hold *different*
  locks, both read "no conflict", and both insert. Two cancels through different
  services can likewise both succeed.
- **Reachability:** not reachable through the shipped `main()`, which builds one
  service. It is a latent defect in the public API.
- **Minimal fix (applied):** `synchronized (store)` instead of the private field
  (3 lines). The store's methods already synchronise on that monitor, and Java
  monitors are reentrant.
- **Regression tests (at original review time, written but not yet run):** two
  tests in `AppointmentConcurrencyTest`; they are included in the subsequently
  passing full suite (see §6).
  They alternate tasks between two services sharing a `SlowAppointmentStore`.
- **Proposed mutation check (not executed):** reintroduce a per-service lock
  (`synchronized (this)`) and confirm the shared-store tests fail.

## 3. Misleading test (corrected)

### T1: `cancelRacingWithRebookingOfSameSlotStaysConsistent` overstated what it proves

It was presented, in a code comment and in DESIGN.md, as verifying race safety.
But cancellation only ever *removes* ACTIVE appointments, so a booking that reads
stale data is just more conservative. This race can't break the invariant even
with no lock, so the test can't detect a missing lock. **Fix:** kept the test (it
still catches deadlocks, unexpected exceptions and invalid outcomes) and rewrote
its comment and the DESIGN.md text to say exactly that. No production code
changed in that correction.

## 4. Remaining risks (not defects against the spec; intentionally not changed)

| ID | Risk | Why not changed |
|---|---|---|
| R1 | Provider IDs are exact, case- and whitespace-sensitive: `"p1 "` ≠ `"p1"`, so one real provider could be double-booked under two spellings | The spec gives no canonical form; silently trimming changes identity. Possible fix: reject IDs with leading/trailing whitespace. Documented in ASSUMPTIONS_AND_LIMITATIONS.md |
| R2 | Direct writes to the public store can still bypass service-level conflict checks | `Appointment` now rejects blank identifiers and non-positive durations; `replace` also rejects provider changes that would corrupt its index. Public `add` can still bypass the booking invariant, so writes should continue to go through the service |
| R3 | One lock serialises all providers | Correct, and contention is negligible in-memory. Per-provider or database-level locking is the scaling path (DESIGN.md §9) |
| R4 | Concurrency tests rely on timing (`SlowAppointmentStore` sleeps) | The sleep widens the race window; mutation testing has not been run |

## 5. Dedicated concurrency review

**Scenario:** two simultaneous requests for provider `p1`: A `[10:00, 11:00)` and
B `[10:30, 11:30)` UTC. They overlap (`10:00 < 11:30` and `10:30 < 11:00`), so
exactly one may succeed.

**1. Where the race could occur.** In `AppointmentService.bookAppointment`,
between the read (`store.findByProvider`) and the write (`store.add`). Without
protection the interleaving *A reads [] → B reads [] → A adds → B adds* stores
both. The HTTP server runs requests on a 16-thread pool, so A and B really do
execute in parallel. The same check-then-act shape exists in `cancelAppointment`
(`findById` → `isActive` → `replace`), where two cancels could both succeed.

**2. Does the current mechanism prevent it?** Yes. There are no database
transactions (the store is in-memory). Instead the whole read-check-write sequence
runs inside `synchronized (store)`. B can't start its read until A has written
and released the monitor, and the Java Memory Model guarantees that releasing a
monitor *happens-before* the next acquisition of it, so B's read sees A's
appointment and B gets `BOOKING_CONFLICT`. The store's per-method
`synchronized` alone would **not** be enough: it makes each call atomic, not the
sequence.

**3. Are conflict checking and persistence atomic?** Yes, with respect to every
other booking and cancellation, because both run in the same critical section on
the same monitor, and `store.add` / `store.replace` are the only writes. Work done
outside the lock (validation, UUID generation) touches no shared state.
*Caveat:* atomicity relies on all writes going through the service. The store's
public `add`/`replace` could bypass it (risk R2).

**4. Can cancellation race with booking?** They're serialised on the same monitor.
For a cancel of an existing `[10:00,11:00)` racing booking B, either the cancel
runs first (B succeeds) or B runs first (B conflicts, then the cancel succeeds).
Both are valid. Even without a lock this race can't violate the invariant:
cancellation only *removes* an active appointment, so a stale read makes the
booking more conservative, never less. The race that *can* go wrong is
cancel-vs-cancel (two successes), and the lock prevents it. With a single monitor
and no other locks taken inside it, there's no lock ordering and therefore no deadlock.

**5. Is the locking strategy appropriate for this persistence mechanism?** Yes.
For an in-memory store in one JVM, a mutex around the compound operation is the
standard, simplest correct choice; transaction isolation and constraints don't
exist here. Tradeoffs: (a) all providers share one lock (fine at this scale; per-
provider striping is the scaling path), and (b) the lock is in-process only. With
several service instances or a real database, the guarantee must move into the
database (exclusion constraint, row lock or SERIALIZABLE; see DESIGN.md §9). An
earlier version scoped the lock to the service object, which was defect D1, now fixed.

**Correction needed at original review time:** none beyond D1, which was already fixed. No production code changed in that review pass.

**Focused test added:** `AppointmentConcurrencyTest.simultaneousRequestsAAndBForSameProviderExactlyOneSucceeds`
(repeated 20 times). It releases A and B together through a barrier against
`SlowAppointmentStore` and asserts: exactly one succeeds; the other fails with
`BookingConflictException` naming the winner; the store contains only the winner.

**Execution status at the time of the original review: NOT RUN.**
`mvn -q test -Dtest=AppointmentConcurrencyTest` failed with
`mvn: command not found`, and `java -version` reported *Unable to locate a Java Runtime*.

**What the test checks:** under the exact A/B interleaving the brief describes,
with a 40 ms gap forced between each thread's read and write, the service never
stores both. The test is repeated 20 times. Mutations M1 (no lock), M9 (write
outside the lock), and M11 (per-service lock) were proposed as checks, but the
mutation suite has not been executed.

**Limitations of the test:**
- It is timing-based. Without the lock it would fail with very high probability
  (barrier release skew is far below the 40 ms window), but not with certainty.
- A pass shows the interleavings exercised are safe. It isn't a proof for all
  interleavings; that rests on the code argument above.
- `SlowAppointmentStore` changes timing (it sleeps inside the lock), so the test
  says nothing about performance or contention.
- It covers one JVM only. It can't test multiple processes or a real database.
- Memory-visibility guarantees come from the Java Memory Model argument, not from the test.

## 6. Follow-up verification (2026-11-15)

The original review-time test status above is historical. On Windows 11 with
Azul Zulu JDK 17.0.12, the full clean verification was subsequently run using
the Maven Wrapper pinned to Maven 3.9.15:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Result: **BUILD SUCCESS; 158 tests, 0 failures, 0 errors, 0 skipped.** This
includes `AppointmentConcurrencyTest`, so the focused A/B concurrency test and
other concurrency tests were executed. The mutation checks listed above were
not run in this follow-up.

This remains AI-assisted technical analysis. The candidate has confirmed
completing a separate independent review; the specific checks and findings
from that review were not supplied here.

The follow-up also hardened two public domain/store boundaries: `Appointment`
now rejects blank identifiers and zero/negative durations at construction, and
`InMemoryAppointmentStore.replace` rejects a provider change before modifying
the record or its provider index. Focused regression tests were added for both.

## 7. Areas checked with no defect found

- Overlap boundaries, adjacency, nanosecond edges, offset normalisation (cases 1–9).
- Cancelled exclusion, including mixed cancelled+active neighbours (10–11).
- Provider isolation by exact key (12).
- No partial state: all validation precedes the lock; a conflict throws before
  the only write; `cancel` writes only after both checks (13–14).
- `getAppointment` reads without the booking lock. That's safe: it is a single
  synchronized store read of an immutable record.
