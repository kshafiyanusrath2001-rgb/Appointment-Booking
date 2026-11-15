package com.example.appointments;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;


public final class UtcTimestamps {

    // The regex enforces the shape (mandatory offset, no offset seconds, no
    // surrounding whitespace); the strict ISO formatter then rejects impossible
    // values such as month 13, 30 February, hour 24 or second 60.
    private static final Pattern FORMAT = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}"
                    + "T[0-9]{2}:[0-9]{2}:[0-9]{2}"
                    + "(\\.[0-9]{1,9})?"
                    + "(Z|[+-][0-9]{2}:[0-9]{2})");

    // Keep normalised values within four-digit years so every value we return can
    // be formatted and parsed back.
    private static final Instant MIN = OffsetDateTime.of(0, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
    private static final Instant MAX_EXCLUSIVE =
            OffsetDateTime.of(10000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();

    private UtcTimestamps() {
    }

    /**
     * Parses {@code value} into a UTC instant.
     *
     * @throws IllegalArgumentException if the value is null, not in the accepted
     *     format, lacks an explicit offset, or denotes an impossible date/time.
     */
    public static Instant parse(String value) {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "must be an ISO 8601 timestamp with an explicit UTC offset, e.g. 2026-01-15T09:00:00Z");
        }
        Instant instant;
        try {
            instant = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("is not a valid date/time");
        }
        if (instant.isBefore(MIN) || !instant.isBefore(MAX_EXCLUSIVE)) {
            throw new IllegalArgumentException("is outside the supported date range");
        }
        return instant;
    }

    /** Formats an instant as ISO 8601 UTC ending in {@code Z}, e.g. 2026-01-15T09:00:00Z. */
    public static String format(Instant value) {
        return DateTimeFormatter.ISO_INSTANT.format(value);
    }
}
