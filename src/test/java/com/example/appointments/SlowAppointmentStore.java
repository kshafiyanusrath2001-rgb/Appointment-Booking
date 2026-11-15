package com.example.appointments;

import java.util.List;
import java.util.Optional;

/**
 * Sleeps after every read and before every write, widening the window in which an
 * unsynchronised check-then-act would race. With this store, removing the service
 * lock (or locking the check but not the write) makes the concurrency tests fail
 * reliably instead of only occasionally.
 */
public class SlowAppointmentStore extends InMemoryAppointmentStore {

    static final long RACE_WINDOW_MILLIS = 20;

    @Override
    public Optional<Appointment> findById(String appointmentId) {
        Optional<Appointment> result = super.findById(appointmentId);
        pause();
        return result;
    }

    @Override
    public List<Appointment> findByProvider(String providerId) {
        List<Appointment> result = super.findByProvider(providerId);
        pause();
        return result;
    }

    @Override
    public void add(Appointment appointment) {
        pause();
        super.add(appointment);
    }

    @Override
    public void replace(Appointment appointment) {
        pause();
        super.replace(appointment);
    }

    private static void pause() {
        try {
            Thread.sleep(RACE_WINDOW_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
