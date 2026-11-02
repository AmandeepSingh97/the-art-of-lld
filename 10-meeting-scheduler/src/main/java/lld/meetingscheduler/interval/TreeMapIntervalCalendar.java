package lld.meetingscheduler.interval;

import lld.meetingscheduler.exception.MeetingConflictException;
import lld.meetingscheduler.model.MeetingOccurrence;
import lld.meetingscheduler.model.TimeInterval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Primary {@link IntervalCalendar} implementation: a {@link TreeMap} of booked occurrences
 * keyed by start {@link Instant}, exploiting the domain invariant that <b>a single
 * resource (one room, one attendee) can never have two overlapping bookings</b>.
 *
 * <h2>The key insight</h2>
 * Because every pair of entries already in the map is guaranteed pairwise non-overlapping
 * (that invariant is what {@link #book} enforces on every insert), the booked intervals form
 * a sorted sequence of disjoint ranges. For a new candidate interval {@code C}, only its
 * immediate neighbours in start-time order can possibly overlap it:
 * <ul>
 *   <li>the <b>predecessor</b> ({@code floorEntry(C.start)}) -- the booked interval with the
 *       largest start &le; C.start. If the predecessor's end is &le; C.start, it cannot
 *       overlap C (disjoint, half-open semantics); and because the map is sorted and disjoint,
 *       every entry *before* the predecessor ends even earlier, so none of them can overlap
 *       C either (transitivity through the sorted-disjoint invariant).</li>
 *   <li>the <b>successor</b> ({@code ceilingEntry(C.start)}) -- the booked interval with the
 *       smallest start &ge; C.start. If the successor's start &ge; C.end, it cannot overlap C;
 *       and every entry *after* the successor starts even later, so none of them overlap C
 *       either.</li>
 * </ul>
 * This reduces an apparently O(n) "scan every booking" conflict check to two
 * {@code O(log n)} navigable-map lookups -- {@link #findConflict} never inspects more than 2
 * existing entries regardless of how many bookings the resource already has.
 *
 * <h2>Complexity</h2>
 * <ul>
 *   <li>{@code findConflict}: O(log n) -- one {@code floorEntry} + one {@code ceilingEntry}.</li>
 *   <li>{@code book}: O(log n) -- a {@code findConflict} plus one {@code TreeMap.put}.</li>
 *   <li>{@code cancel}: O(log n) -- one {@code TreeMap.remove}.</li>
 *   <li>{@code occurrencesOverlapping(window)}: O(log n + k) where k is the number of
 *       overlapping entries returned, via {@code headMap}/{@code tailMap} range views.</li>
 * </ul>
 * Compare this to the naive approach of storing bookings in an unsorted {@code List} and
 * scanning every entry on every booking attempt: O(n) per check, O(n) per booking, and O(n)
 * per free-slot query. At, say, 500 bookings already on a popular room, that is 500 interval
 * comparisons per new booking attempt versus 2. See README §10 for the full alternatives
 * comparison (including the general-purpose {@link AugmentedIntervalTree}).
 *
 * <h2>Thread safety</h2>
 * Every mutating/reading method is {@code synchronized} on this instance as defense-in-depth
 * so a single calendar can never observe/produce a torn {@code TreeMap} state under concurrent
 * access, even in isolation from the rest of the system. The *cross-resource* atomicity
 * guarantee (e.g. "room AND every attendee must simultaneously be free") is a stronger property
 * that this class cannot provide alone -- that is {@link lld.meetingscheduler.service.ResourceLockCoordinator}'s
 * job; see the Concurrency section of the README for the full argument.
 */
public final class TreeMapIntervalCalendar implements IntervalCalendar {

    private final NavigableMap<Instant, MeetingOccurrence> bookingsByStart = new TreeMap<>();
    private final String resourceKey;

    public TreeMapIntervalCalendar(String resourceKey) {
        this.resourceKey = resourceKey;
    }

    @Override
    public synchronized void book(MeetingOccurrence occurrence) {
        Optional<MeetingOccurrence> conflict = findConflict(occurrence.interval());
        if (conflict.isPresent()) {
            throw new MeetingConflictException(resourceKey, occurrence.interval(), conflict.get().interval());
        }
        bookingsByStart.put(occurrence.interval().start(), occurrence);
    }

    @Override
    public synchronized Optional<MeetingOccurrence> findConflict(TimeInterval candidate) {
        Map.Entry<Instant, MeetingOccurrence> predecessor = bookingsByStart.floorEntry(candidate.start());
        if (predecessor != null && predecessor.getValue().interval().overlaps(candidate)) {
            return Optional.of(predecessor.getValue());
        }
        Map.Entry<Instant, MeetingOccurrence> successor = bookingsByStart.ceilingEntry(candidate.start());
        if (successor != null && successor.getValue().interval().overlaps(candidate)) {
            return Optional.of(successor.getValue());
        }
        return Optional.empty();
    }

    @Override
    public synchronized boolean cancel(Instant occurrenceStart) {
        return bookingsByStart.remove(occurrenceStart) != null;
    }

    @Override
    public synchronized List<MeetingOccurrence> occurrencesOverlapping(TimeInterval window) {
        List<MeetingOccurrence> result = new ArrayList<>();
        // The predecessor of window.start() might still overlap the window even though its
        // own start key is before window.start() and therefore outside a naive subMap range.
        Map.Entry<Instant, MeetingOccurrence> predecessor = bookingsByStart.floorEntry(window.start());
        if (predecessor != null && predecessor.getValue().interval().overlaps(window)) {
            result.add(predecessor.getValue());
        }
        for (Map.Entry<Instant, MeetingOccurrence> entry :
                bookingsByStart.subMap(window.start(), true, window.end(), false).entrySet()) {
            if (entry.getValue().interval().overlaps(window)) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    @Override
    public synchronized int size() {
        return bookingsByStart.size();
    }

    public String resourceKey() {
        return resourceKey;
    }
}
