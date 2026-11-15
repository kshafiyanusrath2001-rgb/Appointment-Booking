package com.example.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryAppointmentStoreTest {

    @Test
    void replaceCannotChangeProviderAndCorruptProviderIndex() {
        InMemoryAppointmentStore store = new InMemoryAppointmentStore();
        Appointment original = new Appointment(
                "id", "p1",
                Instant.parse("2026-01-15T10:00:00Z"),
                Instant.parse("2026-01-15T11:00:00Z"),
                AppointmentStatus.ACTIVE);
        store.add(original);

        Appointment changedProvider = new Appointment(
                original.appointmentId(), "p2", original.startTime(), original.endTime(),
                AppointmentStatus.CANCELLED);

        assertThrows(IllegalArgumentException.class, () -> store.replace(changedProvider));
        assertEquals(List.of(original), store.findByProvider("p1"));
        assertEquals(List.of(), store.findByProvider("p2"));
        assertEquals(original, store.findById(original.appointmentId()).orElseThrow());
    }
}
