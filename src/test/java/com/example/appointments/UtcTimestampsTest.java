package com.example.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UtcTimestampsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "2026-01-15T10:00:00Z,            2026-01-15T10:00:00Z",
            "2026-01-15T15:30:00+05:30,       2026-01-15T10:00:00Z",
            "2026-01-14T23:00:00-02:00,       2026-01-15T01:00:00Z",
            "2026-01-15T10:00:00-00:00,       2026-01-15T10:00:00Z",
            "2026-01-15T10:00:00.5Z,          2026-01-15T10:00:00.500Z",
            "2026-01-15T10:00:00.000001Z,     2026-01-15T10:00:00.000001Z",
            "2026-01-15T10:00:00.123456789Z,  2026-01-15T10:00:00.123456789Z",
            "2028-02-29T10:00:00Z,            2028-02-29T10:00:00Z",
            "2026-01-15T10:00:00+18:00,       2026-01-14T16:00:00Z",
            "0000-01-01T00:00:00Z,            0000-01-01T00:00:00Z",
            "9999-12-31T18:59:59-05:00,       9999-12-31T23:59:59Z",
    })
    void acceptsAndNormalisesToUtc(String input, String expectedUtc) {
        Instant parsed = UtcTimestamps.parse(input);
        assertEquals(expectedUtc, UtcTimestamps.format(parsed));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "2026-01-15T10:00:00", // no offset: ambiguous local time
            "2026-01-15", // date only
            "2026-01-15 10:00:00Z", // space separator
            "2026-01-15T10:00Z", // missing seconds
            "2026-01-15T10:00:00+0530", // offset without colon
            "2026-01-15T10:00:00+05:30:00", // offset with seconds (java.time allows it)
            "2026-01-15T10:00:00z", // lowercase z
            "2026-01-15T10:00:00.1234567891Z", // more than nanosecond precision
            "2026-13-01T10:00:00Z", // month 13
            "2026-02-30T10:00:00Z", // 30 February
            "2027-02-29T10:00:00Z", // 29 February in a non-leap year
            "2026-01-15T24:00:00Z", // hour 24
            "2026-01-15T23:59:60Z", // leap second
            "2026-01-15T10:00:00+19:00", // offset beyond +-18:00
            "9999-12-31T19:00:00-05:00", // year 10000 once normalised to UTC
            "0000-01-01T00:00:00+01:00", // year -1 once normalised to UTC
            "+10000-01-01T00:00:00Z", // expanded year
            "2026-01-15T10:00:00Z\n", // trailing newline
            " 2026-01-15T10:00:00Z", // leading whitespace
            "٢٠٢٦-01-15T10:00:00Z", // Arabic-Indic digits
            "1768471200", // epoch seconds
            "not-a-date",
    })
    void rejectsInvalidValues(String input) {
        assertThrows(IllegalArgumentException.class, () -> UtcTimestamps.parse(input));
    }

    @Test
    void formatsWithZSuffix() {
        assertEquals("2026-01-15T10:00:00Z", UtcTimestamps.format(Instant.parse("2026-01-15T10:00:00Z")));
    }
}
