package lld.meetingscheduler.service;

import lld.meetingscheduler.model.TimeInterval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Implements "find common free slots across N attendees (and optionally a room)" using the
 * classic merge-intervals technique: flatten every busy interval from every involved
 * calendar into one list, sort by start, merge overlapping/touching ones into maximal busy
 * blocks, then walk the gaps between consecutive merged blocks (and the window boundaries)
 * looking for gaps at least {@code minDuration} long.
 *
 * <h2>Complexity</h2>
 * Let n be the total number of busy intervals across all involved calendars within the search
 * window. Sorting is O(n log n); the merge pass and the gap-walk are both a single O(n) linear
 * scan. Total: O(n log n), dominated by the sort -- there is no asymptotically better general
 * algorithm here because any correct solution must at least look at every candidate busy
 * interval once, and comparison-based sorting of n items is &Omega;(n log n) in the worst case.
 * (Each individual attendee's busy list is already sorted coming out of
 * {@link lld.meetingscheduler.interval.TreeMapIntervalCalendar#occurrencesOverlapping}; a
 * k-way merge of k already-sorted lists would reduce the practical constant to O(n log k), which
 * is noted in the README as a viable micro-optimization for very large attendee counts, but is
 * not necessary at interview scale and would add complexity without changing the asymptotic
 * class for typical k.)
 */
public final class FreeSlotFinder {

    private FreeSlotFinder() {
    }

    /**
     * @param busyIntervals every busy interval from every involved resource, already clipped (or
     *                      not -- this method clips internally) to the search window
     * @param window        the overall range to search within
     * @param minDuration   minimum gap length to report as a free slot
     */
    public static List<TimeInterval> findCommonFreeSlots(
            List<TimeInterval> busyIntervals, TimeInterval window, Duration minDuration) {
        List<TimeInterval> sorted = new ArrayList<>(busyIntervals);
        Collections.sort(sorted);

        List<TimeInterval> merged = mergeOverlapping(sorted);

        List<TimeInterval> freeSlots = new ArrayList<>();
        var cursor = window.start();
        for (TimeInterval busy : merged) {
            if (busy.end().isBefore(window.start()) || !busy.start().isBefore(window.end())) {
                continue; // entirely outside the search window
            }
            var busyStartClipped = busy.start().isBefore(cursor) ? cursor : busy.start();
            if (busyStartClipped.isAfter(cursor)) {
                Duration gap = Duration.between(cursor, busyStartClipped);
                if (gap.compareTo(minDuration) >= 0) {
                    freeSlots.add(new TimeInterval(cursor, busyStartClipped));
                }
            }
            if (busy.end().isAfter(cursor)) {
                cursor = busy.end();
            }
        }
        if (cursor.isBefore(window.end())) {
            Duration gap = Duration.between(cursor, window.end());
            if (gap.compareTo(minDuration) >= 0) {
                freeSlots.add(new TimeInterval(cursor, window.end()));
            }
        }
        return freeSlots;
    }

    /** Standard merge-intervals pass: input must already be sorted by start. */
    private static List<TimeInterval> mergeOverlapping(List<TimeInterval> sorted) {
        List<TimeInterval> merged = new ArrayList<>();
        for (TimeInterval interval : sorted) {
            if (merged.isEmpty()) {
                merged.add(interval);
                continue;
            }
            TimeInterval last = merged.get(merged.size() - 1);
            // Treat touching intervals (last.end == interval.start) as mergeable too, since a
            // back-to-back pair of busy blocks leaves no free gap between them anyway.
            if (!interval.start().isAfter(last.end())) {
                var newEnd = last.end().isAfter(interval.end()) ? last.end() : interval.end();
                merged.set(merged.size() - 1, new TimeInterval(last.start(), newEnd));
            } else {
                merged.add(interval);
            }
        }
        return merged;
    }
}
