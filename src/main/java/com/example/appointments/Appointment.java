package com.example.appointments;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable appointment occupying a positive-duration half-open interval
 * [startTime, endTime). A status change produces a new instance.
 */
public record Appointment(
        String appointmentId,
        String providerId,
        Instant startTime,
        Instant endTime,
        AppointmentStatus status) {

    public Appointment {
        Objects.requireNonNull(appointmentId, "appointmentId");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(endTime, "endTime");
        Objects.requireNonNull(status, "status");
        if (appointmentId.isBlank()) {
            throw new IllegalArgumentException("appointmentId must not be blank");
        }
        if (providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("endTime must be after startTime");
        }
    }

    public boolean isActive() {
        return status == AppointmentStatus.ACTIVE;
    }

    public boolean overlaps(Instant otherStart, Instant otherEnd) {
        return intervalsOverlap(startTime, endTime, otherStart, otherEnd);
    }

    public Appointment withStatus(AppointmentStatus newStatus) {
        return new Appointment(appointmentId, providerId, startTime, endTime, newStatus);
    }

    /**
     * Returns true if half-open intervals [startA, endA) and [startB, endB) overlap.
     *
     * <p>Two half-open intervals are disjoint exactly when one ends at or before the
     * other starts ({@code endA <= startB || endB <= startA}). Negating that gives the
     * condition below. Touching intervals ({@code endA == startB}) do not overlap.
     */
    public static boolean intervalsOverlap(Instant startA, Instant endA, Instant startB, Instant endB) {
        return startA.isBefore(endB) && startB.isBefore(endA);
    }
}
