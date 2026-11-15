package com.example.appointments;

public final class BookingConflictException extends AppointmentException {

    private final String conflictingAppointmentId;

    public BookingConflictException(String conflictingAppointmentId) {
        super("BOOKING_CONFLICT", "Provider already has an active appointment overlapping the requested time");
        this.conflictingAppointmentId = conflictingAppointmentId;
    }

    public String conflictingAppointmentId() {
        return conflictingAppointmentId;
    }
}
