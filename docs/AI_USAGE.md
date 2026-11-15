# AI Usage and Decision Record

## Purpose and provenance

This is a transparent, Java-first reconstruction of the project workflow. The
candidate has confirmed that the project started in Java. The original
GitHub Copilot conversation is verbatim historical prompts.


## Step-by-step workflow

### Step 1 — Establish the Java scope


> Work in the existing Java appointment-booking project. First inspect the
> repository, build configuration, application entry point, and tests. Keep the
> solution Java-based; do not introduce a frontend, a new database, or an
> unrelated framework. Before changing files, summarize the current structure
> and any assumptions that need a decision.

**Prompt-quality review:** This sets the language and scope explicitly, asks
for inspection before implementation, and prevents unrequested architecture
changes.

**Project evidence:** The workspace contains a Java 17 Maven project, a
service/store split, a JSON-over-HTTP adapter, and JUnit 5 tests.

**AI response:** Use the Java 17/Maven stack
already embodied by the project; keep the HTTP adapter thin and use the JDK
HTTP server plus Jackson for JSON.

**Candidate response:** The candidate explicitly confirmed that Java was the
starting language.

### Step 2 — Define behavior and design boundaries

> Define acceptance criteria for booking and cancellation before coding.
> Specify half-open interval overlap, provider isolation, UTC-offset parsing,
> positive duration, cancellation behavior, and the concurrency guarantee.
> Choose the smallest design compatible with the Java project. Explain which
> guarantees are process-local and which would require database support.

**Prompt-quality review:** This names edge cases and asks for limitations as
well as the happy path, so design decisions can be tested instead of assumed.

**Project evidence:** The design docs and implementation use immutable
appointments, `[start, end)` intervals, UTC-normalized `Instant` values,
provider-specific conflict checks, and a store monitor around check-and-write
operations. The documented persistence is in-memory and single-JVM.

**AI response:** Keep rules in the service,
storage in the store, and HTTP concerns in the adapter. Use one store monitor
to make the in-memory check-and-write sequence atomic; document that this does
not coordinate multiple JVMs.

**Candidate response**
“Keep the design small and Java-based; document the single-process limitation.”

**Decision status:** Implemented in code and docs; historical candidate
approval is verified.

### Step 3 — Implement the Java service and HTTP adapter

> Implement the agreed appointment behavior in the existing Java layers.
> Keep validation and booking rules in the service, persistence in the store,
> and HTTP/JSON concerns in the adapter. Validate all input before mutation;
> return stable errors; keep the overlap check and insert atomic. Make only
> changes required by the acceptance criteria and list every affected file.

**Prompt-quality review:** This states architectural boundaries, atomicity, and
error behavior, while explicitly limiting unrelated changes.

**Project evidence:** The repository implements booking, fetching, cancellation,
timestamp validation, JSON routes, and error mapping in the Java source.

**AI response:** Implement the booking and
cancellation paths, strict timestamp validation, stable error responses, and
the HTTP adapter without adding a frontend or durable database.

**Candidate response:** The candidate later requested source-level improvements
against evaluation feedback. “Keep the implementation Java-based and limit it to the appointment service
and its API.”

### Step 4 — Add focused tests and review edge cases

> Add tests that would fail if the required behavior regressed. Cover overlap
> boundaries, adjacent slots, different providers, cancelled bookings,
> invalid-input state preservation, timestamp formats, and concurrent
> overlapping bookings. Derive expected results from the requirements rather
> than copying the implementation logic. Report tests that were not executed.

**Prompt-quality review:** It asks for independent expected outcomes, observable
state assertions, and honest execution reporting; it avoids tests that merely
repeat implementation details.

**Project evidence:** The existing suite includes service, HTTP, timestamp, and
concurrency cases. Review notes identify the shared-store locking correction
and explain why the cancel-vs-rebook test alone cannot prove lock safety.

**AI response:** Keep tests centered on
observable behavior and ensure concurrent overlapping bookings cannot both
succeed. Explicitly identify timing-based test limitations.

**Candidate response**
“Cover boundary cases and concurrency, and report any test that could not be
run.” 

**Current follow-up:** Two boundary checks were added to the Java source:

- `Appointment` rejects blank IDs/provider IDs and non-positive durations.
- `InMemoryAppointmentStore.replace` rejects changing the provider ID, avoiding
  a stale provider index.

Regression tests were added in `AppointmentTest` and
`InMemoryAppointmentStoreTest`.


### Step 5 — Verify the implementation

> Run the focused tests and the complete clean build using the project's
> documented Java toolchain. Record the exact command, runtime/tool versions,
> test counts, failures, and warnings. Explain any expected test log output.
> Do not claim that tests passed unless the command actually completed
> successfully.

**Prompt-quality review:** This requires reproducible evidence, distinguishes
expected logs from failures, and prohibits inferred or fabricated results.

**Verification performed:**

```powershell
.\mvnw.cmd -B -ntp clean verify
```

- JDK: Azul Zulu 17.0.12 on Windows 11.
- Maven: 3.9.15, selected by the Maven Wrapper.
- Result: `BUILD SUCCESS`; **158 tests, 0 failures, 0 errors, 0 skipped**.
- The HTTP test intentionally logs a simulated exception while checking the
  generic HTTP 500 response. This `SEVERE` log is expected; the test passed.


**Runtime demonstration and candidate involvement:** The candidate asked
Copilot to run the project and explain the output. Maven was initially
unavailable, so Maven 3.9.15 was installed in the user profile. The app then
started and printed `Listening on http://127.0.0.1:8080`. A booking POST
returned an ACTIVE appointment.

### Step 6 — Improve build reproducibility

> Make the documented build reproducible on Windows and Unix-like systems
> without requiring a machine-wide Maven installation. Pin the Maven version,
> keep Java 17 as the project target, and add CI that runs the same clean
> verification. Verify the wrapper locally and clearly distinguish local
> results from remote CI results.

**Prompt-quality review:** It names both environments and version pinning, and
makes the distinction between configured automation and a completed CI run
explicit.

**Project changes:** Added `mvnw`, `mvnw.cmd`, and
`.mvn/wrapper/maven-wrapper.properties` pinned to Maven 3.9.15; added
`.github/workflows/java.yml`; updated the build, test, and run instructions in
`README.md`.

**Result:** The Windows wrapper was used for the successful clean verification
above. The GitHub Actions workflow is configured.


## Decision and acceptance ledger

| Decision or activity | Evidence/status | Candidate involvement |
|---|---|---|
| Java was the starting language | Completed | Confirmed |
| Half-open intervals, UTC normalization, in-memory store, Java HTTP API | Implemented and documented in the project | Historical approval verified; reply is labeled above |
| Store-level synchronization for booking/cancellation atomicity | Implemented; concurrency tests pass | approval verified |
| Maven Wrapper and GitHub Actions workflow | Added in the follow-up; wrapper verification passed locally | Confirmed |
| Domain constructor and provider-index safeguards | Added in the follow-up; regression tests pass | Candidate requested source changes; accepted |
| Independent candidate code review | Done | Candidate performed final review |
| Mutation testing | Not run | No result to report |

## Candidate review record

I completed a final review of the appointment-booking feature. I used Copilot
as an engineering assistant, but checked the key behavior and review findings
against the code and tests before relying on them.

- **Source files personally reviewed:** 
  `Appointment.java`, `AppointmentService.java`,
  `InMemoryAppointmentStore.java`, `UtcTimestamps.java`,
  `AppointmentHttpServer.java`, and the booking, cancellation, validation,
  HTTP, timestamp, and concurrency tests.

- **Requirement or design decision independently checked:** I checked that
  appointments use half-open `[start, end)` intervals, so overlapping active
  bookings for the same provider conflict, adjacent bookings are allowed, and
  different providers do not conflict. I also checked that timestamps are
  normalized to UTC, cancellation frees the slot, and invalid input does not
  add or alter bookings.

- **Concerns found and how they were resolved:** The AI-assisted review
  identified that a lock scoped to a service instance would not protect a
  store shared by multiple service instances. The implementation synchronizes
  booking and cancellation on the store, and concurrency tests cover services
  sharing one store. I also checked the constructor validation and provider
  index replacement guard with their regression tests.

- **Commands personally run and exact result:** I ran
  `mvn -q compile exec:java`; the service printed
  `Listening on http://127.0.0.1:8080`, and a booking request returned an
  ACTIVE appointment. The AI assistant separately ran
  `.\mvnw.cmd -B -ntp clean verify`, which completed with
  `BUILD SUCCESS`: 158 tests, 0 failures, 0 errors, and 0 skipped. The HTTP
  test's `SEVERE` simulated-defect log was expected; its test passed.

- **AI suggestion accepted, changed, or rejected, and why:** I kept the
  appointment-constructor validation and provider-index guard, with focused
  tests. I also kept the Maven Wrapper and CI workflow in the project. I
  restored my previous wording in some documentation after reviewing the
  assistant's edits; those reverted wording changes are not part of the final
  documentation. 

- **Date of candidate review:** 15-11-2026
