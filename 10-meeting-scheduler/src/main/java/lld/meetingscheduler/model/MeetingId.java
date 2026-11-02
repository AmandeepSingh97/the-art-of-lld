package lld.meetingscheduler.model;

import java.util.Objects;
import java.util.UUID;

/** Strongly typed identifier for a {@link Meeting} series. See {@link UserId} for the rationale. */
public record MeetingId(String value) {

    public MeetingId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("MeetingId value must not be blank");
        }
    }

    public static MeetingId newId() {
        return new MeetingId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
