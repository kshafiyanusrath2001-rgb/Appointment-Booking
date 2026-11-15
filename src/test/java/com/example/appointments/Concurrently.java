package com.example.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Test helpers for running tasks simultaneously and checking the booking invariant. */
public final class Concurrently {

    public static final Duration GRID = Duration.ofMinutes(15);

    private Concurrently() {
    }

    public record Outcome<T>(T value, Throwable error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** Runs each task on its own thread, all released together by a barrier. */
    public static <T> List<Outcome<T>> run(List<Callable<T>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return task.call();
                }));
            }
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    outcomes.add(new Outcome<>(future.get(30, TimeUnit.SECONDS), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome<>(null, e.getCause()));
                } catch (TimeoutException e) {
                    throw new AssertionError("task hung", e);
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Independent oracle for the invariant (deliberately not using the overlap
     * formula under test): marks every 15-minute grid cell covered by an ACTIVE
     * appointment and fails if any provider has a cell covered twice. Only valid
     * for appointments aligned to the 15-minute grid.
     */
    public static void assertNoDoubleBooking(InMemoryAppointmentStore store) {
        Map<String, Integer> occupied = new HashMap<>();
        for (Appointment appointment : store.findAll()) {
            if (appointment.status() != AppointmentStatus.ACTIVE) {
                continue;
            }
            for (Instant cell = appointment.startTime(); cell.isBefore(appointment.endTime()); cell = cell.plus(GRID)) {
                occupied.merge(appointment.providerId() + "@" + cell, 1, Integer::sum);
            }
        }
        Map<String, Integer> doubled = new HashMap<>();
        occupied.forEach((cell, count) -> {
            if (count > 1) {
                doubled.put(cell, count);
            }
        });
        assertEquals(Map.of(), doubled, "provider double-booked");
    }
}
