package lld.meetingscheduler.interval;

import lld.meetingscheduler.model.MeetingId;
import lld.meetingscheduler.model.MeetingOccurrence;
import lld.meetingscheduler.model.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeMapIntervalCalendarTest {

    private static Instant at(int hour) {
        return Instant.parse(String.format("2025-01-01T%02d:00:00Z", hour));
    }

    private static MeetingOccurrence occurrence(int startHour, int endHour) {
        return new MeetingOccurrence(MeetingId.newId(), new TimeInterval(at(startHour), at(endHour)));
    }

    @Test
    void bookingNonOverlappingSlotsSucceeds() {
        TreeMapIntervalCalendar calendar = new TreeMapIntervalCalendar("ROOM:1");
        calendar.book(occurrence(9, 10));
        calendar.book(occurrence(10, 11)); // back-to-back, must succeed (half-open semantics)
        calendar.book(occurrence(14, 15));
        assertEquals(3, calendar.size());
    }

    @Test
    void bookingOverlappingSlotThrowsConflict() {
        TreeMapIntervalCalendar calendar = new TreeMapIntervalCalendar("ROOM:1");
        calendar.book(occurrence(9, 11));
        var conflict = occurrence(10, 12);
        assertThrows(lld.meetingscheduler.exception.MeetingConflictException.class,
                () -> calendar.book(conflict));
        assertEquals(1, calendar.size(), "the conflicting booking must not have been inserted");
    }

    @Test
    void conflictDetectedAgainstPredecessorOnly() {
        TreeMapIntervalCalendar calendar = new TreeMapIntervalCalendar("ROOM:1");
        calendar.book(occurrence(9, 10));
        // Candidate starts inside [9,10) -> predecessor (floorEntry) must catch it even though
        // there is no entry whose *start* equals the candidate's start.
        assertTrue(calendar.findConflict(new TimeInterval(at(9).plusSeconds(1800), at(11))).isPresent());
    }

    @Test
    void conflictDetectedAgainstSuccessorOnly() {
        TreeMapIntervalCalendar calendar = new TreeMapIntervalCalendar("ROOM:1");
        calendar.book(occurrence(11, 12));
        // Candidate starts before the only booking but extends into it -> successor (ceilingEntry)
        // must catch it.
        assertTrue(calendar.findConflict(new TimeInterval(at(10), at(11).plusSeconds(1800))).isPresent());
    }

    @Test
    void noConflictWhenGapExistsBetweenNeighbours() {
        TreeMapIntervalCalendar calendar = new TreeMapIntervalCalendar("ROOM:1");
        calendar.book(occurrence(9, 10));
        calendar.book(occurrence(14, 15));
        // Candidate sits entirely in the gap between the two bookings; neither neighbour overlaps.
        assertFalse(calendar.findConflict(new TimeInterval(at(11), at(12))).isPresent());
    }

    @Test
    void cancelRemovesBookingAndFreesSlot() {
        TreeMapIntervalCalendar calendar = new TreeMapIntervalCalendar("ROOM:1");
        calendar.book(occurrence(9, 10));
        assertTrue(calendar.cancel(at(9)));
        assertEquals(0, calendar.size());
        calendar.book(occurrence(9, 10)); // now succeeds again
        assertEquals(1, calendar.size());
    }

    @Test
    void cancelOfUnknownStartReturnsFalse() {
        TreeMapIntervalCalendar calendar = new TreeMapIntervalCalendar("ROOM:1");
        assertFalse(calendar.cancel(at(9)));
    }

    @Test
    void occurrencesOverlappingReturnsOnlyOverlappingEntriesIncludingPredecessor() {
        TreeMapIntervalCalendar calendar = new TreeMapIntervalCalendar("ROOM:1");
        calendar.book(occurrence(8, 10));  // overlaps a window starting at 9 (predecessor case)
        calendar.book(occurrence(11, 12)); // fully inside window
        calendar.book(occurrence(20, 21)); // outside window entirely

        List<MeetingOccurrence> overlapping =
                calendar.occurrencesOverlapping(new TimeInterval(at(9), at(13)));
        assertEquals(2, overlapping.size());
    }
}
