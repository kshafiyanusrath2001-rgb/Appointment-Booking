package com.example.appointments;

public final class ValidationException extends AppointmentException {

    private final String field;

    public ValidationException(String field, String problem) {
        super("VALIDATION_ERROR", field + " " + problem);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
