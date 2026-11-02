package lld.meetingscheduler.model;

import java.util.Objects;
import java.util.UUID;

/** Strongly typed identifier for a {@link Room}. See {@link UserId} for the rationale. */
public record RoomId(String value) {

    public RoomId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("RoomId value must not be blank");
        }
    }

    public static RoomId newId() {
        return new RoomId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
