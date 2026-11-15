package com.example.appointments;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Booking and cancellation business logic.
 *
 * <p>Invariant: for a given provider, no two ACTIVE appointments overlap, using
 * half-open intervals [startTime, endTime).
 *
 * <p>Concurrency: "check for conflicts, then insert" and "check status, then cancel"
 * run while holding the store's monitor, making them atomic with respect to each
 * other. Without it, two overlapping bookings could both pass the conflict check
 * before either is stored, and two cancellations could both report success. The lock
 * is the store instance rather than a field of this class, so the invariant also
 * holds when several services share one store.
 */
public final class AppointmentService {

    private final InMemoryAppointmentStore store;

    public AppointmentService() {
        this(new InMemoryAppointmentStore());
    }

    public AppointmentService(InMemoryAppointmentStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Books an appointment. Timestamps use the format described in {@link UtcTimestamps}.
     *
     * @throws ValidationException if any input is missing or invalid
     * @throws BookingConflictException if the provider has an overlapping ACTIVE appointment
     */
    public Appointment bookAppointment(String providerId, String startTime, String endTime) {
        // Validation touches no shared state, so it runs outside the lock.
        String provider = requireIdentifier("providerId", providerId);
        Instant start = requireTimestamp("startTime", startTime);
        Instant end = requireTimestamp("endTime", endTime);
        if (!end.isAfter(start)) {
            throw new ValidationException("endTime", "must be after startTime");
        }

        Appointment appointment = new Appointment(
                UUID.randomUUID().toString(), provider, start, end, AppointmentStatus.ACTIVE);
        synchronized (store) {
            for (Appointment existing : store.findByProvider(provider)) {
                if (existing.isActive() && existing.overlaps(start, end)) {
                    throw new BookingConflictException(existing.appointmentId());
                }
            }
            store.add(appointment);
        }
        return appointment;
    }

    /**
     * Cancels an ACTIVE appointment and returns the updated record. Repeat cancellation
     * is reported, not treated as success.
     *
     * @throws ValidationException if the ID is missing
     * @throws AppointmentNotFoundException if no such appointment exists
     * @throws AppointmentAlreadyCancelledException if it is already cancelled
     */
    public Appointment cancelAppointment(String appointmentId) {
        String id = requireIdentifier("appointmentId", appointmentId);
        synchronized (store) {
            Appointment existing = store.findById(id)
                    .orElseThrow(() -> new AppointmentNotFoundException(id));
            if (!existing.isActive()) {
                throw new AppointmentAlreadyCancelledException(id);
            }
            Appointment cancelled = existing.withStatus(AppointmentStatus.CANCELLED);
            store.replace(cancelled);
            return cancelled;
        }
    }

    public Appointment getAppointment(String appointmentId) {
        String id = requireIdentifier("appointmentId", appointmentId);
        return store.findById(id).orElseThrow(() -> new AppointmentNotFoundException(id));
    }

    private static String requireIdentifier(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field, "is required");
        }
        return value;
    }

    private static Instant requireTimestamp(String field, String value) {
        if (value == null || value.isEmpty()) {
            throw new ValidationException(field, "is required");
        }
        try {
            return UtcTimestamps.parse(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(field, e.getMessage());
        }
    }
}
