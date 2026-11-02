package lld.meetingscheduler.interval;

import lld.meetingscheduler.model.MeetingId;
import lld.meetingscheduler.model.MeetingOccurrence;
import lld.meetingscheduler.model.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AugmentedIntervalTreeTest {

    private static Instant at(int hour) {
        return Instant.parse(String.format("2025-01-01T%02d:00:00Z", hour));
    }

    private static MeetingOccurrence occurrence(int startHour, int endHour) {
        return new MeetingOccurrence(MeetingId.newId(), new TimeInterval(at(startHour), at(endHour)));
    }

    @Test
    void supportsMultipleOverlappingBookingsUnlikeDisjointCalendar() {
        AugmentedIntervalTree tree = new AugmentedIntervalTree();
        tree.insert(occurrence(9, 11));
        tree.insert(occurrence(10, 12)); // deliberately overlaps the first -- legal for this structure
        tree.insert(occurrence(15, 16));

        assertEquals(3, tree.size());
        List<MeetingOccurrence> overlaps = tree.findAllOverlapping(new TimeInterval(at(10), at(11)));
        assertEquals(2, overlaps.size());
    }

    @Test
    void findAllOverlappingPrunesNonOverlappingSubtrees() {
        AugmentedIntervalTree tree = new AugmentedIntervalTree();
        for (int hour = 0; hour < 20; hour += 2) {
            tree.insert(occurrence(hour, hour + 1));
        }
        List<MeetingOccurrence> overlaps = tree.findAllOverlapping(new TimeInterval(at(10), at(11)));
        assertEquals(1, overlaps.size());
        assertTrue(overlaps.get(0).interval().overlaps(new TimeInterval(at(10), at(11))));
    }

    @Test
    void noOverlapReturnsEmptyList() {
        AugmentedIntervalTree tree = new AugmentedIntervalTree();
        tree.insert(occurrence(9, 10));
        assertTrue(tree.findAllOverlapping(new TimeInterval(at(11), at(12))).isEmpty());
    }
}
