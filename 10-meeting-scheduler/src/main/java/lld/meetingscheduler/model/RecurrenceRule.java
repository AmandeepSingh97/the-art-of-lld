package lld.meetingscheduler.model;

import lld.meetingscheduler.enums.Frequency;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A simplified, RRULE-inspired recurrence rule covering the two frequencies the problem
 * explicitly asks for (daily / weekly). Deliberately not a full RFC-5545 RRULE parser --
 * that is a well-known rabbit hole (RFC 5545 §3.3.10 recurrence rules run for pages,
 * covering MONTHLY/YEARLY with BYSETPOS, BYMONTHDAY, negative offsets, etc.) that would
 * dominate the interview without adding signal. The shape below is intentionally close
 * enough to RRULE that extending it (e.g. adding MONTHLY) is a mechanical addition of one
 * enum value plus one {@link lld.meetingscheduler.strategy.RecurrenceExpansionStrategy}.
 *
 * <p>Exactly one of {@code until} / {@code count} must be set -- an unbounded recurrence
 * is intentionally disallowed because expansion must always terminate (see
 * {@link #MAX_OCCURRENCES}); real calendar systems cap this too (Google Calendar caps
 * recurring events at a fixed number of future instances for the same reason).
 */
public final class RecurrenceRule {

    /** Hard safety cap on expanded occurrences, independent of count/until, to bound memory
     *  and CPU for a single booking call -- protects against a pathological "every day until
     *  year 9999" request degrading the service for every other tenant. */
    public static final int MAX_OCCURRENCES = 730; // ~2 years of daily occurrences

    private final Frequency frequency;
    private final int interval;
    private final Set<DayOfWeek> daysOfWeek;
    private final LocalDate until;
    private final Integer count;

    private RecurrenceRule(
            Frequency frequency, int interval, Set<DayOfWeek> daysOfWeek, LocalDate until, Integer count) {
        this.frequency = Objects.requireNonNull(frequency, "frequency must not be null");
        if (interval <= 0) {
            throw new IllegalArgumentException("interval must be positive, got " + interval);
        }
        this.interval = interval;
        this.daysOfWeek = daysOfWeek == null ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(daysOfWeek);
        this.until = until;
        this.count = count;

        if (frequency == Frequency.WEEKLY && this.daysOfWeek.isEmpty()) {
            throw new IllegalArgumentException("WEEKLY recurrence requires at least one day-of-week");
        }
        if ((until == null) == (count == null)) {
            throw new IllegalArgumentException("exactly one of until/count must be provided");
        }
        if (count != null && (count <= 0 || count > MAX_OCCURRENCES)) {
            throw new IllegalArgumentException(
                    "count must be in (0, " + MAX_OCCURRENCES + "], got " + count);
        }
    }

    public static RecurrenceRule daily(int interval, LocalDate until) {
        return new RecurrenceRule(Frequency.DAILY, interval, null, until, null);
    }

    public static RecurrenceRule daily(int interval, int count) {
        return new RecurrenceRule(Frequency.DAILY, interval, null, null, count);
    }

    public static RecurrenceRule weekly(int interval, Set<DayOfWeek> daysOfWeek, LocalDate until) {
        return new RecurrenceRule(Frequency.WEEKLY, interval, daysOfWeek, until, null);
    }

    public static RecurrenceRule weekly(int interval, Set<DayOfWeek> daysOfWeek, int count) {
        return new RecurrenceRule(Frequency.WEEKLY, interval, daysOfWeek, null, count);
    }

    public Frequency frequency() {
        return frequency;
    }

    public int interval() {
        return interval;
    }

    public Set<DayOfWeek> daysOfWeek() {
        return EnumSet.copyOf(daysOfWeek.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : daysOfWeek);
    }

    public LocalDate until() {
        return until;
    }

    public Integer count() {
        return count;
    }

    @Override
    public String toString() {
        return "RecurrenceRule{frequency=" + frequency + ", interval=" + interval
                + ", daysOfWeek=" + daysOfWeek + ", until=" + until + ", count=" + count + "}";
    }
}
