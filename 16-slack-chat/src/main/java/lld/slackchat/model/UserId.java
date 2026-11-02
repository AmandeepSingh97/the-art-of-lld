package lld.slackchat.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Type-safe identifier for a {@link User}.
 *
 * <p>Using a dedicated value object instead of a bare {@code String}/{@code UUID} prevents a
 * whole class of bugs where a {@code ChannelId} is accidentally passed where a {@code UserId}
 * is expected -- the compiler catches it instead of a unit test (or production incident).
 * This is a deliberate, low-cost design choice, not over-engineering: it is a single record
 * with zero behaviour beyond identity.
 */
public record UserId(String value) {

    public UserId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("UserId value must not be blank");
        }
    }

    public static UserId generate() {
        return new UserId(UUID.randomUUID().toString());
    }

    public static UserId of(String value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
