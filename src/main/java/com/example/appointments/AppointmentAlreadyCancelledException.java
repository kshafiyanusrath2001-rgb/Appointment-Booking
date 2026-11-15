package com.example.appointments;

public final class AppointmentAlreadyCancelledException extends AppointmentException {

    private final String appointmentId;

    public AppointmentAlreadyCancelledException(String appointmentId) {
        super("APPOINTMENT_ALREADY_CANCELLED", "Appointment " + appointmentId + " is already cancelled");
        this.appointmentId = appointmentId;
    }

    public String appointmentId() {
        return appointmentId;
    }
}
