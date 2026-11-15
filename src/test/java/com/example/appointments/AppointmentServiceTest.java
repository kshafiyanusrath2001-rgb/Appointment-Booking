package com.example.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AppointmentServiceTest {

    private static final String START = "2026-01-15T10:00:00Z";
    private static final String END = "2026-01-15T11:00:00Z";

    private InMemoryAppointmentStore store;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryAppointmentStore();
        service = new AppointmentService(store);
    }

    private List<Appointment> snapshot() {
        return store.findAll().stream().sorted(Comparator.comparing(Appointment::appointmentId)).toList();
    }

    private static String at(String time) {
        return "2026-01-15T" + time + "Z";
    }

    @Nested
    class Booking {

        @Test
        void successfulBooking() {
            Appointment appointment = service.bookAppointment("p1", START, END);

            assertTrue(!appointment.appointmentId().isBlank());
            assertEquals("p1", appointment.providerId());
            assertEquals(Instant.parse(START), appointment.startTime());
            assertEquals(Instant.parse(END), appointment.endTime());
            assertEquals(AppointmentStatus.ACTIVE, appointment.status());
            assertEquals(appointment, store.findById(appointment.appointmentId()).orElseThrow());
        }

        @Test
        void eachBookingGetsADistinctId() {
            Appointment a = service.bookAppointment("p1", at("10:00:00"), at("11:00:00"));
            Appointment b = service.bookAppointment("p1", at("11:00:00"), at("12:00:00"));
            assertNotEquals(a.appointmentId(), b.appointmentId());
        }

        @Test
        void rejectsOverlappingAppointmentForSameProvider() {
            Appointment existing = service.bookAppointment("p1", START, END);
            List<Appointment> before = snapshot();

            BookingConflictException e = assertThrows(BookingConflictException.class,
                    () -> service.bookAppointment("p1", at("10:30:00"), at("11:30:00")));

            assertEquals(existing.appointmentId(), e.conflictingAppointmentId());
            assertEquals(before, snapshot());
        }

        @Test
        void allowsOverlappingTimesForDifferentProviders() {
            service.bookAppointment("p1", START, END);
            Appointment other = service.bookAppointment("p2", START, END);
            assertTrue(other.isActive());
            assertEquals(2, store.findAll().size());
        }

        @Test
        void providerIdsAreComparedExactly() {
            service.bookAppointment("p1", START, END);
            // Opaque identifiers: "P1" is a different provider from "p1".
            service.bookAppointment("P1", START, END);
            assertEquals(2, store.findAll().size());
        }

        @Test
        void allowsAdjacentAppointmentsOnBothSides() {
            service.bookAppointment("p1", START, END);
            assertTrue(service.bookAppointment("p1", at("09:00:00"), at("10:00:00")).isActive());
            assertTrue(service.bookAppointment("p1", at("11:00:00"), at("12:00:00")).isActive());
        }

        // Expected outcomes are written out by hand, not derived from the overlap formula.
        @ParameterizedTest(name = "{0}: [{1}, {2}) conflicts with [10:00, 11:00)? {3}")
        @CsvSource({
                "identical,                 10:00:00,        11:00:00,        true",
                "strictly inside,           10:15:00,        10:45:00,        true",
                "strictly containing,       09:00:00,        12:00:00,        true",
                "overlaps start,            09:30:00,        10:30:00,        true",
                "overlaps end,              10:30:00,        11:30:00,        true",
                "same start ends earlier,   10:00:00,        10:30:00,        true",
                "starts later same end,     10:30:00,        11:00:00,        true",
                "same start ends later,     10:00:00,        12:00:00,        true",
                "starts earlier same end,   09:00:00,        11:00:00,        true",
                "1ns overlap at start,      09:00:00,        10:00:00.000000001, true",
                "1ns overlap at end,        10:59:59.999999999, 12:00:00,     true",
                "adjacent before,           09:00:00,        10:00:00,        false",
                "adjacent after,            11:00:00,        12:00:00,        false",
                "disjoint before,           08:00:00,        09:00:00,        false",
                "disjoint after,            12:00:00,        13:00:00,        false",
        })
        void overlapBoundariesAgainstExisting10To11(String label, String start, String end, boolean conflicts) {
            service.bookAppointment("p1", START, END);

            if (conflicts) {
                assertThrows(BookingConflictException.class, () -> service.bookAppointment("p1", at(start), at(end)));
                assertEquals(1, store.findAll().size());
            } else {
                service.bookAppointment("p1", at(start), at(end));
                assertEquals(2, store.findAll().size());
            }
        }

        @Test
        void conflictDetectionComparesInstantsNotStrings() {
            // 10:00Z-11:00Z expressed in +05:30 is 15:30-16:30.
            service.bookAppointment("p1", START, END);
            assertThrows(BookingConflictException.class,
                    () -> service.bookAppointment("p1", "2026-01-15T16:00:00+05:30", "2026-01-15T17:00:00+05:30"));

            // Adjacent once normalised: 16:30+05:30 == 11:00Z.
            Appointment adjacent =
                    service.bookAppointment("p1", "2026-01-15T16:30:00+05:30", "2026-01-15T17:30:00+05:30");
            assertEquals(Instant.parse("2026-01-15T11:00:00Z"), adjacent.startTime());
        }

        @Test
        void multipleActiveAppointmentsForOneProvider() {
            service.bookAppointment("p1", at("09:00:00"), at("10:00:00"));
            service.bookAppointment("p1", at("11:00:00"), at("12:00:00"));
            service.bookAppointment("p1", at("13:00:00"), at("14:00:00"));

            // Overlaps the middle one only.
            assertThrows(BookingConflictException.class,
                    () -> service.bookAppointment("p1", at("11:30:00"), at("12:30:00")));
            // Spans a gap and two existing appointments.
            assertThrows(BookingConflictException.class,
                    () -> service.bookAppointment("p1", at("09:30:00"), at("11:30:00")));
            // Exactly fills the gaps.
            service.bookAppointment("p1", at("10:00:00"), at("11:00:00"));
            service.bookAppointment("p1", at("12:00:00"), at("13:00:00"));

            assertEquals(5, store.findByProvider("p1").size());
        }

        @Test
        void cancelledAppointmentNoLongerBlocksSlot() {
            Appointment first = service.bookAppointment("p1", START, END);
            service.cancelAppointment(first.appointmentId());

            Appointment rebooked = service.bookAppointment("p1", START, END);

            assertTrue(rebooked.isActive());
            assertNotEquals(first.appointmentId(), rebooked.appointmentId());
            // The rebooked slot blocks again.
            BookingConflictException e = assertThrows(BookingConflictException.class,
                    () -> service.bookAppointment("p1", at("10:30:00"), at("10:45:00")));
            assertEquals(rebooked.appointmentId(), e.conflictingAppointmentId());
        }
    }

    @Nested
    class Validation {

        private void assertRejected(String field, String providerId, String start, String end) {
            ValidationException e = assertThrows(ValidationException.class,
                    () -> service.bookAppointment(providerId, start, end));
            assertEquals(field, e.field());
            assertEquals(List.of(), store.findAll());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        void rejectsMissingProviderId(String providerId) {
            assertRejected("providerId", providerId, START, END);
        }

        @ParameterizedTest
        @NullAndEmptySource
        void rejectsMissingStartTime(String start) {
            assertRejected("startTime", "p1", start, END);
        }

        @ParameterizedTest
        @NullAndEmptySource
        void rejectsMissingEndTime(String end) {
            assertRejected("endTime", "p1", START, end);
        }

        @ParameterizedTest
        @ValueSource(strings = {"tomorrow", "2026-01-15T10:00:00", "2026-01-15", "2026-02-30T10:00:00Z", "10"})
        void rejectsMalformedTimestamps(String bad) {
            assertRejected("startTime", "p1", bad, END);
            assertRejected("endTime", "p1", START, bad);
        }

        @Test
        void rejectsTimestampsOutsideSupportedRangeAfterNormalisation() {
            // Start is 9999-12-31T23:00Z (valid); end is 10000-01-01T00:00Z (out of range).
            assertRejected("endTime", "p1", "9999-12-31T18:00:00-05:00", "9999-12-31T19:00:00-05:00");
        }

        @Test
        void rejectsZeroDuration() {
            assertRejected("endTime", "p1", START, START);
        }

        @Test
        void rejectsZeroDurationAcrossOffsets() {
            // Same instant written two ways.
            assertRejected("endTime", "p1", "2026-01-15T10:00:00Z", "2026-01-15T11:00:00+01:00");
        }

        @Test
        void rejectsNegativeDuration() {
            assertRejected("endTime", "p1", END, START);
        }

        @Test
        void acceptsSmallestPositiveDuration() {
            Appointment appointment =
                    service.bookAppointment("p1", "2026-01-15T10:00:00Z", "2026-01-15T10:00:00.000000001Z");
            assertEquals(Duration.ofNanos(1), Duration.between(appointment.startTime(), appointment.endTime()));
        }

        static Stream<Arguments> invalidRequests() {
            return Stream.of(
                    Arguments.of(null, at("12:00:00"), at("13:00:00")),
                    Arguments.of("", at("12:00:00"), at("13:00:00")),
                    Arguments.of("p1", null, at("13:00:00")),
                    Arguments.of("p1", at("12:00:00"), null),
                    Arguments.of("p1", "garbage", at("13:00:00")),
                    Arguments.of("p1", "2026-01-15T12:00:00", at("13:00:00")),
                    Arguments.of("p1", at("12:00:00"), at("12:00:00")),
                    Arguments.of("p1", at("13:00:00"), at("12:00:00")),
                    Arguments.of("p1", at("10:30:00"), at("10:45:00"))); // conflict
        }

        @ParameterizedTest
        @MethodSource("invalidRequests")
        void invalidRequestsDoNotModifyStoredData(String providerId, String start, String end) {
            Appointment existing = service.bookAppointment("p1", START, END);
            List<Appointment> before = snapshot();

            assertThrows(AppointmentException.class, () -> service.bookAppointment(providerId, start, end));

            assertEquals(before, snapshot());
            assertTrue(store.findById(existing.appointmentId()).orElseThrow().isActive());
        }
    }

    @Nested
    class Cancellation {

        @Test
        void cancelsActiveAppointment() {
            Appointment appointment = service.bookAppointment("p1", START, END);

            Appointment cancelled = service.cancelAppointment(appointment.appointmentId());

            assertEquals(appointment.withStatus(AppointmentStatus.CANCELLED), cancelled);
            assertEquals(AppointmentStatus.CANCELLED,
                    store.findById(appointment.appointmentId()).orElseThrow().status());
        }

        @Test
        void cancellingNonexistentAppointmentIsReported() {
            service.bookAppointment("p1", START, END);
            List<Appointment> before = snapshot();

            AppointmentNotFoundException e = assertThrows(AppointmentNotFoundException.class,
                    () -> service.cancelAppointment("does-not-exist"));

            assertEquals("does-not-exist", e.appointmentId());
            assertEquals(before, snapshot());
        }

        @Test
        void cancellingAlreadyCancelledAppointmentIsReported() {
            Appointment appointment = service.bookAppointment("p1", START, END);
            service.cancelAppointment(appointment.appointmentId());
            List<Appointment> before = snapshot();

            AppointmentAlreadyCancelledException e = assertThrows(AppointmentAlreadyCancelledException.class,
                    () -> service.cancelAppointment(appointment.appointmentId()));

            assertEquals(appointment.appointmentId(), e.appointmentId());
            assertEquals(before, snapshot());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        void rejectsMissingAppointmentId(String appointmentId) {
            ValidationException e = assertThrows(ValidationException.class,
                    () -> service.cancelAppointment(appointmentId));
            assertEquals("appointmentId", e.field());
        }

        @Test
        void cancellingOneAppointmentDoesNotAffectOthers() {
            Appointment target = service.bookAppointment("p1", at("10:00:00"), at("11:00:00"));
            Appointment sameProvider = service.bookAppointment("p1", at("11:00:00"), at("12:00:00"));
            Appointment otherProvider = service.bookAppointment("p2", at("10:00:00"), at("11:00:00"));

            service.cancelAppointment(target.appointmentId());

            assertEquals(sameProvider, store.findById(sameProvider.appointmentId()).orElseThrow());
            assertEquals(otherProvider, store.findById(otherProvider.appointmentId()).orElseThrow());
            // The neighbouring slot is still protected.
            assertThrows(BookingConflictException.class,
                    () -> service.bookAppointment("p1", at("10:30:00"), at("11:30:00")));
        }

        @Test
        void cancelledRecordIsRetained() {
            Appointment appointment = service.bookAppointment("p1", START, END);
            service.cancelAppointment(appointment.appointmentId());
            assertEquals(AppointmentStatus.CANCELLED, service.getAppointment(appointment.appointmentId()).status());
        }

        @Test
        void getUnknownAppointment() {
            assertThrows(AppointmentNotFoundException.class, () -> service.getAppointment("missing"));
        }
    }
}
