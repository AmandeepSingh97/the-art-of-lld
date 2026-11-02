package lld.meetingscheduler.exception;

import lld.meetingscheduler.model.TimeInterval;

/**
 * Thrown when a candidate occurrence overlaps an already-booked occurrence on some
 * resource (room or attendee). Carries enough structured context (resource key, the two
 * conflicting intervals) for a caller/UI to explain *why* the booking failed, rather than a
 * bare message string.
 */
public final class MeetingConflictException extends RuntimeException {

    private final String resourceKey;
    private final TimeInterval requested;
    private final TimeInterval conflictingWith;

    public MeetingConflictException(String resourceKey, TimeInterval requested, TimeInterval conflictingWith) {
        super("Resource '" + resourceKey + "' has a conflict: requested " + requested
                + " overlaps existing booking " + conflictingWith);
        this.resourceKey = resourceKey;
        this.requested = requested;
        this.conflictingWith = conflictingWith;
    }

    public String resourceKey() {
        return resourceKey;
    }

    public TimeInterval requested() {
        return requested;
    }

    public TimeInterval conflictingWith() {
        return conflictingWith;
    }
}
