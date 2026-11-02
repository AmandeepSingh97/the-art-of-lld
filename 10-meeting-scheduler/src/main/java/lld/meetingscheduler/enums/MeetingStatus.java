package lld.meetingscheduler.enums;

/**
 * Lifecycle status of a {@link lld.meetingscheduler.model.Meeting} series (not of a single
 * occurrence -- occurrence-level cancellation is tracked via exception dates on the
 * meeting itself, see {@link lld.meetingscheduler.model.Meeting#exceptionDates()}).
 */
public enum MeetingStatus {
    SCHEDULED,
    CANCELLED
}
