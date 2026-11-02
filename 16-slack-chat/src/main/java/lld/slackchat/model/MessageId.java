package lld.slackchat.model;

import java.util.Objects;
import java.util.UUID;

/** Type-safe identifier for a {@link Message}. */
public record MessageId(String value) {

    public MessageId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("MessageId value must not be blank");
        }
    }

    public static MessageId generate() {
        return new MessageId(UUID.randomUUID().toString());
    }

    public static MessageId of(String value) {
        return new MessageId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
