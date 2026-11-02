package lld.meetingscheduler.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * An immutable, half-open time range {@code [start, end)}, always stored and compared in
 * UTC ({@link Instant}). This is the single core value object the entire conflict-detection
 * and free-slot-finding engine is built on.
 *
 * <h2>Why half-open ({@code [start, end)}) semantics</h2>
 * A meeting ending at 10:00 and another starting at 10:00 do <b>not</b> conflict in real
 * calendar systems (back-to-back meetings are legal and extremely common). Half-open
 * interval semantics give the correct, boundary-exclusive overlap test for free with a
 * single comparison expression (see {@link #overlaps(TimeInterval)}), whereas inclusive
 * {@code [start, end]} semantics would require special-casing the touching-boundary case
 * everywhere it is used.
 *
 * <h2>Why UTC {@link Instant} and not {@link java.time.ZonedDateTime}</h2>
 * Storing a {@code ZonedDateTime} (or worse, a floating local time) as the source of truth
 * invites exactly the class of bug real calendar products have shipped: a recurring 9am
 * meeting silently shifting by an hour across a daylight-saving transition because the
 * stored offset became stale. By normalizing to {@link Instant} (an absolute point on the
 * UTC time line) at the storage boundary, every comparison, sort, and overlap check is a
 * trivial, unambiguous {@code long} comparison. Timezone is reattached only at the
 * presentation edge -- see {@link lld.meetingscheduler.util.TimeZoneUtil}.
 */
public record TimeInterval(Instant start, Instant end) implements Comparable<TimeInterval> {

    public TimeInterval {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(
                    "end (" + end + ") must be strictly after start (" + start + ")");
        }
    }

    /**
     * Half-open overlap test: two intervals overlap iff each starts before the other ends.
     * This single expression is correct for every relative position of two intervals
     * (disjoint, touching, nested, partially overlapping, identical) and treats
     * touching-boundary intervals (this.end == other.start) as non-overlapping.
     */
    public boolean overlaps(TimeInterval other) {
        Objects.requireNonNull(other, "other must not be null");
        return this.start.isBefore(other.end) && other.start.isBefore(this.end);
    }

    /** Whether the given instant falls within {@code [start, end)}. */
    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return !instant.isBefore(start) && instant.isBefore(end);
    }

    public Duration duration() {
        return Duration.between(start, end);
    }

    /** Returns a new interval shifted by {@code amount} (used by recurrence expansion). */
    public TimeInterval shiftedBy(Duration amount) {
        return new TimeInterval(start.plus(amount), end.plus(amount));
    }

    /**
     * Natural ordering: by start instant first, then by end instant. This ordering is what
     * allows {@link lld.meetingscheduler.interval.TreeMapIntervalCalendar} to use a
     * {@link java.util.TreeMap} keyed by start instant and still reason about neighbours
     * correctly.
     */
    @Override
    public int compareTo(TimeInterval other) {
        int byStart = this.start.compareTo(other.start);
        return byStart != 0 ? byStart : this.end.compareTo(other.end);
    }

    @Override
    public String toString() {
        return "[" + start + " -> " + end + ")";
    }
}
