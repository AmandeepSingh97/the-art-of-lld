package lld.slackchat.model;

import java.util.Objects;
import java.util.UUID;

/** Type-safe identifier for a {@link Channel} (public, private, or direct-message). */
public record ChannelId(String value) {

    public ChannelId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ChannelId value must not be blank");
        }
    }

    public static ChannelId generate() {
        return new ChannelId(UUID.randomUUID().toString());
    }

    public static ChannelId of(String value) {
        return new ChannelId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
