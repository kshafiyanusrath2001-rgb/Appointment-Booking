package com.example.appointments;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AppointmentTest {

    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant END = Instant.parse("2026-01-15T11:00:00Z");

    @Test
    void rejectsBlankIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> appointment(" ", "p1", START, END));
        assertThrows(IllegalArgumentException.class, () -> appointment("id", " ", START, END));
    }

    @Test
    void rejectsZeroOrNegativeDuration() {
        assertThrows(IllegalArgumentException.class, () -> appointment("id", "p1", START, START));
        assertThrows(IllegalArgumentException.class, () -> appointment("id", "p1", END, START));
    }

    private static Appointment appointment(String id, String provider, Instant start, Instant end) {
        return new Appointment(id, provider, start, end, AppointmentStatus.ACTIVE);
    }
}
