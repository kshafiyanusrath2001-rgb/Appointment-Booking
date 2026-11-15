package com.example.appointments;

/** Base class for domain errors. {@link #code()} is a stable, machine-readable identifier. */
public abstract class AppointmentException extends RuntimeException {

    private final String code;

    protected AppointmentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
