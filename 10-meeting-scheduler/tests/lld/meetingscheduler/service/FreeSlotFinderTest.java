package lld.meetingscheduler.service;

import lld.meetingscheduler.model.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeSlotFinderTest {

    private static Instant at(int hour) {
        return Instant.parse(String.format("2025-01-01T%02d:00:00Z", hour));
    }

    @Test
    void noBusyIntervalsReturnsEntireWindow() {
        TimeInterval window = new TimeInterval(at(9), at(17));
        List<TimeInterval> free = FreeSlotFinder.findCommonFreeSlots(List.of(), window, Duration.ofMinutes(30));
        assertEquals(1, free.size());
        assertEquals(window, free.get(0));
    }

    @Test
    void mergesOverlappingBusyIntervalsBeforeComputingGaps() {
        TimeInterval window = new TimeInterval(at(9), at(17));
        List<TimeInterval> busy = List.of(
                new TimeInterval(at(10), at(12)),
                new TimeInterval(at(11), at(13)) // overlaps the first; must be merged into [10,13)
        );
        List<TimeInterval> free = FreeSlotFinder.findCommonFreeSlots(busy, window, Duration.ofMinutes(30));
        assertEquals(List.of(
                new TimeInterval(at(9), at(10)),
                new TimeInterval(at(13), at(17))
        ), free);
    }

    @Test
    void gapsShorterThanMinDurationAreExcluded() {
        TimeInterval window = new TimeInterval(at(9), at(17));
        List<TimeInterval> busy = List.of(
                new TimeInterval(at(10), at(10).plusSeconds(45 * 60)), // 10:00 -> 10:45
                new TimeInterval(at(11), at(12))                        // 11:00 -> 12:00, leaves a 15-min gap
        );
        List<TimeInterval> free = FreeSlotFinder.findCommonFreeSlots(busy, window, Duration.ofMinutes(30));
        // The 15-minute gap between 10:45 and 11:00 is shorter than the 30-minute minimum and
        // must not appear in the result.
        assertTrue(free.stream().noneMatch(slot -> slot.duration().compareTo(Duration.ofMinutes(30)) < 0));
        assertEquals(List.of(
                new TimeInterval(at(9), at(10)),
                new TimeInterval(at(12), at(17))
        ), free);
    }

    @Test
    void busyIntervalTouchingWindowBoundaryLeavesNoGapThere() {
        TimeInterval window = new TimeInterval(at(9), at(17));
        List<TimeInterval> busy = List.of(new TimeInterval(at(9), at(10)));
        List<TimeInterval> free = FreeSlotFinder.findCommonFreeSlots(busy, window, Duration.ofMinutes(30));
        assertEquals(List.of(new TimeInterval(at(10), at(17))), free);
    }

    @Test
    void multipleAttendeesBusyIntervalsCombineIntoSharedFreeSlots() {
        TimeInterval window = new TimeInterval(at(9), at(17));
        // Alice busy 9-10 and 14-15; Bob busy 11-12.
        List<TimeInterval> busy = List.of(
                new TimeInterval(at(9), at(10)),
                new TimeInterval(at(14), at(15)),
                new TimeInterval(at(11), at(12))
        );
        List<TimeInterval> free = FreeSlotFinder.findCommonFreeSlots(busy, window, Duration.ofMinutes(30));
        assertEquals(List.of(
                new TimeInterval(at(10), at(11)),
                new TimeInterval(at(12), at(14)),
                new TimeInterval(at(15), at(17))
        ), free);
    }
}
