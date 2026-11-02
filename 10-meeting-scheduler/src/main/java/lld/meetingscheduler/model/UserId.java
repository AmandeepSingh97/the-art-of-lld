package lld.meetingscheduler.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identifier for a {@link User}.
 *
 * <p>Modelled as an immutable value object (not a bare {@code String}/{@code UUID}) so the
 * compiler prevents accidentally passing a {@link RoomId} or {@link MeetingId} where a
 * {@code UserId} is expected -- a class of bug that is otherwise only caught at runtime
 * (or not at all) once every identifier is "just a String".
 */
public record UserId(String value) {

    public UserId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("UserId value must not be blank");
        }
    }

    public static UserId newId() {
        return new UserId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
