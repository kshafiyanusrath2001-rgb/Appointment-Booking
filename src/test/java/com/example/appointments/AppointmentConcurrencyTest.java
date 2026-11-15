package com.example.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.appointments.Concurrently.Outcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class AppointmentConcurrencyTest {

    private static final String START = "2026-01-15T10:00:00Z";
    private static final String END = "2026-01-15T11:00:00Z";

    @Test
    void simultaneousOverlappingBookingsExactlyOneSucceeds() throws Exception {
        SlowAppointmentStore store = new SlowAppointmentStore();
        AppointmentService service = new AppointmentService(store);
        // Ten different intervals that all contain 10:30, so every pair overlaps.
        List<Callable<Appointment>> tasks = new ArrayList<>();
        for (int minute = 0; minute < 30; minute += 3) {
            String start = String.format("2026-01-15T10:%02d:00Z", minute);
            String end = String.format("2026-01-15T11:%02d:00Z", minute);
            tasks.add(() -> service.bookAppointment("p1", start, end));
        }

        List<Outcome<Appointment>> outcomes = Concurrently.run(tasks);

        List<Appointment> successes = outcomes.stream().filter(Outcome::ok).map(Outcome::value).toList();
        assertEquals(1, successes.size());
        for (Outcome<Appointment> outcome : outcomes) {
            if (!outcome.ok()) {
                BookingConflictException e = assertInstanceOf(BookingConflictException.class, outcome.error());
                assertEquals(successes.get(0).appointmentId(), e.conflictingAppointmentId());
            }
        }
        assertEquals(successes, store.findAll());
    }

    // Focused scenario from the concurrency review: request A [10:00, 11:00) and
    // request B [10:30, 11:30) for the same provider, released together by a barrier.
    // SlowAppointmentStore holds each thread for 20 ms after its read and 20 ms before
    // its write, so without atomic check-and-insert both would read "no conflict" and
    // both would be stored. Repeated so that either request can be the winner.
    @RepeatedTest(20)
    void simultaneousRequestsAAndBForSameProviderExactlyOneSucceeds() throws Exception {
        SlowAppointmentStore store = new SlowAppointmentStore();
        AppointmentService service = new AppointmentService(store);
        List<Callable<Appointment>> requests = List.of(
                () -> service.bookAppointment("p1", "2026-01-15T10:00:00Z", "2026-01-15T11:00:00Z"),
                () -> service.bookAppointment("p1", "2026-01-15T10:30:00Z", "2026-01-15T11:30:00Z"));

        List<Outcome<Appointment>> outcomes = Concurrently.run(requests);

        List<Appointment> winners = outcomes.stream().filter(Outcome::ok).map(Outcome::value).toList();
        assertEquals(1, winners.size(), "exactly one of A and B must succeed");
        Outcome<Appointment> loser = outcomes.stream().filter(o -> !o.ok()).findFirst().orElseThrow();
        BookingConflictException conflict = assertInstanceOf(BookingConflictException.class, loser.error());
        assertEquals(winners.get(0).appointmentId(), conflict.conflictingAppointmentId());
        assertEquals(winners, store.findAll());
    }

    @Test
    void simultaneousBookingsForDifferentProvidersAllSucceed() throws Exception {
        AppointmentService service = new AppointmentService(new SlowAppointmentStore());
        List<Callable<Appointment>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String provider = "p" + i;
            tasks.add(() -> service.bookAppointment(provider, START, END));
        }
        assertTrue(Concurrently.run(tasks).stream().allMatch(Outcome::ok));
    }

    @Test
    void simultaneousAdjacentBookingsAllSucceed() throws Exception {
        AppointmentService service = new AppointmentService(new SlowAppointmentStore());
        List<Callable<Appointment>> tasks = new ArrayList<>();
        for (int hour = 9; hour < 15; hour++) {
            String start = String.format("2026-01-15T%02d:00:00Z", hour);
            String end = String.format("2026-01-15T%02d:00:00Z", hour + 1);
            tasks.add(() -> service.bookAppointment("p1", start, end));
        }
        assertTrue(Concurrently.run(tasks).stream().allMatch(Outcome::ok));
    }

    @Test
    void simultaneousCancellationsExactlyOneSucceeds() throws Exception {
        SlowAppointmentStore store = new SlowAppointmentStore();
        AppointmentService service = new AppointmentService(store);
        Appointment appointment = service.bookAppointment("p1", START, END);
        Callable<Appointment> cancel = () -> service.cancelAppointment(appointment.appointmentId());

        List<Outcome<Appointment>> outcomes = Concurrently.run(Collections.nCopies(8, cancel));

        assertEquals(1, outcomes.stream().filter(Outcome::ok).count());
        outcomes.stream().filter(o -> !o.ok())
                .forEach(o -> assertInstanceOf(AppointmentAlreadyCancelledException.class, o.error()));
        assertEquals(AppointmentStatus.CANCELLED,
                store.findById(appointment.appointmentId()).orElseThrow().status());
    }

    // Regression: the lock used to be a field of AppointmentService, so two services
    // sharing one store each had their own lock and could both book the same slot.
    @Test
    void servicesSharingOneStoreStillAllowOnlyOneOverlappingBooking() throws Exception {
        SlowAppointmentStore store = new SlowAppointmentStore();
        List<AppointmentService> services = List.of(new AppointmentService(store), new AppointmentService(store));
        List<Callable<Appointment>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            AppointmentService service = services.get(i % 2);
            tasks.add(() -> service.bookAppointment("p1", START, END));
        }

        List<Outcome<Appointment>> outcomes = Concurrently.run(tasks);

        assertEquals(1, outcomes.stream().filter(Outcome::ok).count());
        outcomes.stream().filter(o -> !o.ok())
                .forEach(o -> assertInstanceOf(BookingConflictException.class, o.error()));
        assertEquals(1, store.findAll().size());
    }

    @Test
    void servicesSharingOneStoreStillAllowOnlyOneCancellation() throws Exception {
        SlowAppointmentStore store = new SlowAppointmentStore();
        AppointmentService first = new AppointmentService(store);
        AppointmentService second = new AppointmentService(store);
        String id = first.bookAppointment("p1", START, END).appointmentId();
        List<Callable<Appointment>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            AppointmentService service = i % 2 == 0 ? first : second;
            tasks.add(() -> service.cancelAppointment(id));
        }

        List<Outcome<Appointment>> outcomes = Concurrently.run(tasks);

        assertEquals(1, outcomes.stream().filter(Outcome::ok).count());
        outcomes.stream().filter(o -> !o.ok())
                .forEach(o -> assertInstanceOf(AppointmentAlreadyCancelledException.class, o.error()));
    }

    // Consistency check only: cancellation can only shrink the set of ACTIVE
    // appointments, so a booking that reads stale data is merely more conservative.
    // This race therefore cannot break the invariant even without a lock, and this
    // test does not detect a missing lock (the tests above do). It guards against
    // deadlocks, unexpected exceptions and invalid outcomes.
    @RepeatedTest(10)
    void cancelRacingWithRebookingOfSameSlotStaysConsistent() throws Exception {
        SlowAppointmentStore store = new SlowAppointmentStore();
        AppointmentService service = new AppointmentService(store);
        Appointment original = service.bookAppointment("p1", START, END);

        List<Callable<Appointment>> tasks = List.of(
                () -> service.cancelAppointment(original.appointmentId()),
                () -> service.bookAppointment("p1", "2026-01-15T10:30:00Z", "2026-01-15T11:30:00Z"));

        List<Outcome<Appointment>> outcomes = Concurrently.run(tasks);

        // Cancellation always succeeds. The booking may legitimately win or lose
        // depending on ordering, but must never coexist with an active original.
        assertTrue(outcomes.get(0).ok());
        if (!outcomes.get(1).ok()) {
            assertInstanceOf(BookingConflictException.class, outcomes.get(1).error());
        }
        Concurrently.assertNoDoubleBooking(store);
    }

    @Test
    void invariantHoldsUnderRandomConcurrentBookingsAndCancellations() throws Exception {
        InMemoryAppointmentStore store = new InMemoryAppointmentStore();
        AppointmentService service = new AppointmentService(store);
        List<String> providers = List.of("p1", "p2", "p3");
        List<String> bookedIds = Collections.synchronizedList(new ArrayList<>());
        Instant origin = Instant.parse("2026-01-15T00:00:00Z");

        List<Callable<Void>> workers = new ArrayList<>();
        for (int seed = 0; seed < 8; seed++) {
            Random random = new Random(seed);
            workers.add(() -> {
                for (int op = 0; op < 300; op++) {
                    if (!bookedIds.isEmpty() && random.nextDouble() < 0.3) {
                        String target;
                        synchronized (bookedIds) {
                            target = bookedIds.get(random.nextInt(bookedIds.size()));
                        }
                        try {
                            service.cancelAppointment(target);
                        } catch (AppointmentAlreadyCancelledException ignored) {
                            // another worker cancelled it first
                        }
                    } else {
                        Instant start = origin.plus(Concurrently.GRID.multipliedBy(random.nextInt(40)));
                        Instant end = start.plus(Concurrently.GRID.multipliedBy(1 + random.nextInt(5)));
                        try {
                            Appointment booked = service.bookAppointment(
                                    providers.get(random.nextInt(providers.size())),
                                    UtcTimestamps.format(start), UtcTimestamps.format(end));
                            bookedIds.add(booked.appointmentId());
                        } catch (BookingConflictException ignored) {
                            // expected when the slot is taken
                        }
                    }
                }
                return null;
            });
        }

        List<Outcome<Void>> outcomes = Concurrently.run(workers);

        outcomes.forEach(o -> assertTrue(o.ok(), () -> "worker failed: " + o.error()));
        assertTrue(!bookedIds.isEmpty());
        Concurrently.assertNoDoubleBooking(store);
    }
}
