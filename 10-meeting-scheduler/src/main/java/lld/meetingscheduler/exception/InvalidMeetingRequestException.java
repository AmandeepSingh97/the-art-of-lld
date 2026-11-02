package lld.meetingscheduler.exception;

/**
 * Thrown for request-shape validation failures that are caught before any lock is taken or
 * any calendar is touched (e.g. empty attendee list, malformed recurrence rule, past-dated
 * meeting start). Kept distinct from {@link MeetingConflictException}, which is a
 * domain-level "the slot is busy" outcome rather than a malformed request.
 */
public final class InvalidMeetingRequestException extends RuntimeException {
    public InvalidMeetingRequestException(String message) {
        super(message);
    }
}
