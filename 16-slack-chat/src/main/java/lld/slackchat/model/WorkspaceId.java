package lld.slackchat.model;

import java.util.Objects;
import java.util.UUID;

/** Type-safe identifier for a {@link Workspace}. See {@link UserId} for the rationale. */
public record WorkspaceId(String value) {

    public WorkspaceId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("WorkspaceId value must not be blank");
        }
    }

    public static WorkspaceId generate() {
        return new WorkspaceId(UUID.randomUUID().toString());
    }

    public static WorkspaceId of(String value) {
        return new WorkspaceId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
