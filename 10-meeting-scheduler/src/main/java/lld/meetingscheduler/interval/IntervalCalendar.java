package lld.meetingscheduler.interval;

import lld.meetingscheduler.model.MeetingOccurrence;
import lld.meetingscheduler.model.TimeInterval;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Abstraction over "the set of booked occurrences for one resource" (one room, or one
 * attendee's calendar). This is the seam the DSA discussion in the README hangs off: the
 * production implementation ({@link TreeMapIntervalCalendar}) exploits a domain invariant
 * (occurrences on a single resource never overlap each other) to get O(log n) conflict
 * checks; {@link AugmentedIntervalTree} is kept alongside as the general-purpose
 * "any-overlap, overlaps allowed" alternative discussed (but not used on the hot path) so
 * the trade-off is concrete and testable rather than asserted.
 */
public interface IntervalCalendar {

    /**
     * Atomically checks for a conflict and, if none exists, inserts {@code occurrence}.
     *
     * @throws lld.meetingscheduler.exception.MeetingConflictException if {@code occurrence}'s
     *         interval overlaps an existing booking on this resource
     */
    void book(MeetingOccurrence occurrence);

    /**
     * Pure conflict check (no mutation) -- used when a caller needs to validate multiple
     * occurrences across multiple calendars before committing any of them (see
     * {@link lld.meetingscheduler.service.SchedulerService}, which needs all-or-nothing
     * semantics across every occurrence of a recurring meeting and every involved resource).
     *
     * @return the first conflicting occurrence found, if any
     */
    Optional<MeetingOccurrence> findConflict(TimeInterval candidate);

    /** Removes the occurrence starting exactly at {@code occurrenceStart}, if present. */
    boolean cancel(Instant occurrenceStart);

    /** All booked occurrences whose interval overlaps {@code window}, sorted by start time. */
    List<MeetingOccurrence> occurrencesOverlapping(TimeInterval window);

    /** Total number of booked occurrences on this resource (for diagnostics/tests). */
    int size();
}
