package lld.meetingscheduler.model;

import java.util.List;
import java.util.Objects;

/**
 * Result of a successful booking: the created series id and every concrete occurrence that
 * was scheduled (a singleton list for a one-off meeting; one entry per expanded occurrence
 * for a recurring meeting).
 */
public record BookingResult(MeetingId meetingId, List<TimeInterval> scheduledOccurrences) {

    public BookingResult {
        Objects.requireNonNull(meetingId, "meetingId must not be null");
        scheduledOccurrences = List.copyOf(
                Objects.requireNonNull(scheduledOccurrences, "scheduledOccurrences must not be null"));
        if (scheduledOccurrences.isEmpty()) {
            throw new IllegalArgumentException("scheduledOccurrences must not be empty");
        }
    }
}
