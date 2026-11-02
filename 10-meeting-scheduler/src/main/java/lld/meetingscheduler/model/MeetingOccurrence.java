package lld.meetingscheduler.model;

/**
 * A single materialized instance of a (possibly recurring) {@link Meeting}, at a concrete
 * {@link TimeInterval}. This is the unit of storage inside
 * {@link lld.meetingscheduler.interval.IntervalCalendar}: calendars index occurrences, not
 * meeting series, because conflict detection and free-slot search both operate on concrete
 * time ranges. A non-recurring meeting simply has exactly one occurrence.
 */
public record MeetingOccurrence(MeetingId meetingId, TimeInterval interval) {

    public MeetingOccurrence {
        if (meetingId == null) {
            throw new IllegalArgumentException("meetingId must not be null");
        }
        if (interval == null) {
            throw new IllegalArgumentException("interval must not be null");
        }
    }
}
