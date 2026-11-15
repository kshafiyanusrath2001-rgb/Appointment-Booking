# Review: Booking Conflicts and Concurrency

**What was checked:** interval edges, adjacency, cancelled records, provider
separation, rejected-request state, and races between concurrent operations.
**Approach:** outcomes were written from the requirements and then checked
against the implementation. This is AI-assisted analysis. The candidate has
also confirmed completing a separate review, but its specific steps are not
recorded here. The original test status below is historical; §6 reports the
later full-suite result.

## 1. Expected results at interval boundaries

Reference case: provider `p1` already has an ACTIVE appointment from 10:00 to
11:00 UTC.

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


**Reasoning behind the predicate:** the intervals do not intersect if the
existing one ends before the request (`E <= s`) or the request ends before the
existing one (`e <= S`). Negating those cases yields `S < e && s < E`. The
implementation applies this strict comparison in `Appointment.java`; equal
end/start times therefore remain adjacent rather than conflicting.

## 2. Confirmed defect

### D1: The lock originally protected a service, not its shared store

- **Earlier implementation:** the service used a private lock object.
- **Failure mode:** two `AppointmentService` instances backed by the same
  `InMemoryAppointmentStore` would synchronize on different objects. Both
  could observe a free interval and insert, or both could report a successful
  cancellation. Sharing a store is supported by the public constructor, even
  though the shipped `main()` creates only one service.
- **Change made:** the service now synchronizes on the store itself. Its
  synchronized methods use that same reentrant monitor.
- **Regression coverage:** concurrency cases alternate requests between
  services sharing one `SlowAppointmentStore`. They are included in the
  passing full run in §6; at the first review they had been written but not run.
- **Not performed:** a mutation run that restores the service-owned lock and
  verifies these tests fail.

## 3. Misleading test (corrected)

### T1: The cancel/rebook race test needed a narrower claim

An earlier comment implied this test proves the booking invariant under a
cancel/rebook race. Cancellation only removes an ACTIVE blocker, however, so a
stale read could only make a booking more conservative; this scenario cannot
expose a missing lock. The test remains useful for checking valid outcomes,
unexpected exceptions, and deadlocks, but it is not evidence that the lock is
necessary. Its comment and the design text were corrected without changing
production code.

## 4. Remaining risks (not defects against the spec; intentionally not changed)

| ID | Risk | Why not changed |
|---|---|---|
| R1 | Provider IDs are case- and whitespace-sensitive, so one real provider can be represented by different strings | No canonical form was specified. Trimming could merge distinct IDs; rejecting surrounding whitespace remains an option documented in ASSUMPTIONS_AND_LIMITATIONS.md |
| R2 | Direct calls to the store can skip the conflict rule | The model checks basic fields and the store protects its index on replacement, but `add` is not a booking operation. Use the service for writes |
| R3 | A single monitor serializes bookings across providers | Correct but limits parallelism; provider-level or database synchronization is a scaling option (DESIGN.md §9) |
| R4 | The concurrency tests widen a timing window rather than proving every possible schedule | `SlowAppointmentStore` improves the chance of exposing a race; no mutation testing was run |

## 5. Dedicated concurrency review

**Case examined:** provider `p1` receives A `[10:00, 11:00)` and B
`[10:30, 11:30)` at nearly the same time. Since the intervals intersect,
exactly one booking should be accepted.

**1. Race location.** The booking hazard lies between `findByProvider` and
`add` in `bookAppointment`. If A and B both read before either writes, both
could conclude the slot is free. Cancellation also has a compound
lookup/check/replace sequence that could otherwise allow two successful cancels.
The HTTP server uses a 16-thread executor, so requests are not inherently
serialized by the adapter.

**2. What prevents it now?** There is no database transaction: the data is
in-memory. Instead, the service holds `synchronized (store)` across the entire
read-check-write sequence. The second caller waits until the first releases the
monitor, then observes the new appointment and receives `BOOKING_CONFLICT`.
Synchronizing each store method alone would not protect the gap between calls.

**3. Is the operation atomic relative to other service calls?** Yes. Booking
and cancellation use the same monitor, and validation and UUID creation happen
before entering it without touching shared state. This guarantee assumes writes
go through the service; direct store operations can bypass it (risk R2).

**4. What if booking and cancellation overlap in time?** The monitor orders
them. If cancellation happens first, the new booking can proceed; otherwise,
the still-active record causes a conflict before the cancellation completes.
Either outcome is valid. The more serious race is two cancellations of the
same record both succeeding, which the lock prevents.

**5. Does this fit the current store?** For one in-memory store in one JVM, a
monitor around each compound operation is a small and direct solution. Its
limits are global contention and lack of coordination across processes. If the
service gains multiple instances or database persistence, the invariant needs
to move to database constraints or transaction-level protection (DESIGN.md §9).
The earlier service-owned lock is the defect described in D1.

**Production change during the dedicated review:** none beyond the D1 fix,
which was already present.

**Focused test added:** `AppointmentConcurrencyTest.simultaneousRequestsAAndBForSameProviderExactlyOneSucceeds`
(repeated 20 times). It releases A and B together through a barrier against
`SlowAppointmentStore` and asserts: exactly one succeeds; the other fails with
`BookingConflictException` naming the winner; the store contains only the winner.

**Status during the initial review: NOT RUN.**
`mvn -q test -Dtest=AppointmentConcurrencyTest` failed with
`mvn: command not found`, and `java -version` reported *Unable to locate a Java Runtime*.

**Test scope:** with a 40 ms delay around store operations, the test repeatedly
releases A and B together and checks that both are never stored. It runs 20
times. Proposed mutations M1 (remove the lock), M9 (write outside the lock),
and M11 (use a per-service lock) were not executed.

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

The initial test status above was superseded by a later full run. On Windows 11,
using Azul Zulu JDK 17.0.12 and the Maven Wrapper pinned to Maven 3.9.15, the
following clean verification completed:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Result: **BUILD SUCCESS; 158 tests, 0 failures, 0 errors, and 0 skipped.** The
run included `AppointmentConcurrencyTest`, including the repeated A/B case.
The proposed mutation checks remain unrun.

This document records AI-assisted analysis. The candidate separately confirmed
an independent review; its individual checks and findings are not detailed here.

Two boundary checks were also added in the follow-up: `Appointment` rejects
blank identifiers and non-positive durations, while `replace` rejects
provider changes before altering either the record or its index. Each has a
focused regression test.

## 7. Areas checked with no defect found

- Interval edges, adjacency, nanosecond overlaps, and offset normalization (cases 1–9).
- Exclusion of cancelled records, including a mix of cancelled and active neighbors (10–11).
- Provider separation using exact identifiers (12).
- No partial writes: validation occurs before locking, conflicts exit before insertion, and cancellation replaces a record only after both checks pass (13–14).
- `getAppointment` needs no compound-operation lock; it reads one immutable record through the synchronized store method.
