package lld.meetingscheduler.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeIntervalTest {

    private static Instant at(int hour) {
        return Instant.parse(String.format("2025-01-01T%02d:00:00Z", hour));
    }

    @Test
    void rejectsEndNotAfterStart() {
        assertThrows(IllegalArgumentException.class, () -> new TimeInterval(at(10), at(10)));
        assertThrows(IllegalArgumentException.class, () -> new TimeInterval(at(10), at(9)));
    }

    @Test
    void overlappingIntervalsReportOverlap() {
        TimeInterval a = new TimeInterval(at(9), at(10));
        TimeInterval b = new TimeInterval(at(9).plusSeconds(1), at(11));
        assertTrue(a.overlaps(b));
        assertTrue(b.overlaps(a));
    }

    @Test
    void touchingBoundaryIsNotOverlap() {
        // [9,10) and [10,11) share the boundary instant but must not be reported as conflicting --
        // back-to-back meetings are legal.
        TimeInterval a = new TimeInterval(at(9), at(10));
        TimeInterval b = new TimeInterval(at(10), at(11));
        assertFalse(a.overlaps(b));
        assertFalse(b.overlaps(a));
    }

    @Test
    void disjointIntervalsDoNotOverlap() {
        TimeInterval a = new TimeInterval(at(9), at(10));
        TimeInterval b = new TimeInterval(at(11), at(12));
        assertFalse(a.overlaps(b));
    }

    @Test
    void nestedIntervalOverlaps() {
        TimeInterval outer = new TimeInterval(at(9), at(12));
        TimeInterval inner = new TimeInterval(at(10), at(11));
        assertTrue(outer.overlaps(inner));
        assertTrue(inner.overlaps(outer));
    }

    @Test
    void containsIsHalfOpen() {
        TimeInterval interval = new TimeInterval(at(9), at(10));
        assertTrue(interval.contains(at(9)));
        assertFalse(interval.contains(at(10)));
    }

    @Test
    void naturalOrderingSortsByStartThenEnd() {
        TimeInterval early = new TimeInterval(at(9), at(10));
        TimeInterval later = new TimeInterval(at(10), at(11));
        assertTrue(early.compareTo(later) < 0);
        assertTrue(later.compareTo(early) > 0);
        assertEquals(0, early.compareTo(new TimeInterval(at(9), at(10))));
    }
}
