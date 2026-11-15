package com.example.appointments;

public final class AppointmentNotFoundException extends AppointmentException {

    private final String appointmentId;

    public AppointmentNotFoundException(String appointmentId) {
        super("APPOINTMENT_NOT_FOUND", "Appointment " + appointmentId + " does not exist");
        this.appointmentId = appointmentId;
    }

    public String appointmentId() {
        return appointmentId;
    }
}
