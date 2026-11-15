package com.example.appointments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory appointment persistence. Holds data only; enforces no business rules.
 *
 * <p>Each method is synchronized on this instance so that individual reads and writes
 * are safe and visible across threads. That does <em>not</em> make "check for
 * conflicts, then insert" atomic: two callers can both read before either writes.
 * Callers needing a compound operation to be atomic hold this instance's monitor
 * around it, as {@link AppointmentService} does. Because the monitor is reentrant,
 * the individual methods can still be called while it is held.
 */
public class InMemoryAppointmentStore {

    private final Map<String, Appointment> byId = new LinkedHashMap<>();
    private final Map<String, List<String>> idsByProvider = new HashMap<>();

    public synchronized Optional<Appointment> findById(String appointmentId) {
        return Optional.ofNullable(byId.get(appointmentId));
    }

    /** Returns all appointments (any status) for a provider. */
    public synchronized List<Appointment> findByProvider(String providerId) {
        return idsByProvider.getOrDefault(providerId, List.of()).stream().map(byId::get).toList();
    }

    public synchronized List<Appointment> findAll() {
        return List.copyOf(byId.values());
    }

    public synchronized void add(Appointment appointment) {
        if (byId.putIfAbsent(appointment.appointmentId(), appointment) != null) {
            throw new IllegalStateException("Duplicate appointment ID " + appointment.appointmentId());
        }
        idsByProvider.computeIfAbsent(appointment.providerId(), k -> new ArrayList<>())
                .add(appointment.appointmentId());
    }

    /** Replaces an existing record with the same ID (and provider). */
    public synchronized void replace(Appointment appointment) {
        Appointment existing = byId.get(appointment.appointmentId());
        if (existing == null) {
            throw new IllegalStateException("Unknown appointment ID " + appointment.appointmentId());
        }
        if (!existing.providerId().equals(appointment.providerId())) {
            throw new IllegalArgumentException("Cannot change provider for appointment " + appointment.appointmentId());
        }
        byId.put(appointment.appointmentId(), appointment);
    }
}
