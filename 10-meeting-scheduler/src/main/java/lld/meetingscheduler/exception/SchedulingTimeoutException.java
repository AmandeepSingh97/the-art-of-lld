package lld.meetingscheduler.exception;

/**
 * Thrown when {@link lld.meetingscheduler.service.ResourceLockCoordinator} cannot acquire
 * every required per-resource lock within the configured timeout. This is intentionally a
 * distinct, retryable failure mode (think: HTTP 409/429-equivalent) rather than a deadlock --
 * the ordered lock-acquisition protocol (see the coordinator's javadoc) makes deadlock
 * structurally impossible, but bounded contention under heavy concurrent load on the same
 * room/attendee is still possible and must fail fast rather than block forever.
 */
public final class SchedulingTimeoutException extends RuntimeException {
    public SchedulingTimeoutException(String message) {
        super(message);
    }
}
