package lld.contactmanagement.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A named label a contact can belong to (e.g. "Family", "Work", "Vendors").
 * Membership is modeled on {@link Contact} as a set of group ids rather than
 * on {@code Group} as a set of contact ids - see {@code IndexManager}'s
 * reverse (group -> contact ids) index for the efficient lookup direction.
 * This keeps {@code Group} a simple, independently-addressable value entity
 * instead of an aggregate that must stay in lock-step with every contact
 * edit.
 */
public record Group(UUID id, String name) {

    public Group {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    public static Group create(String name) {
        return new Group(UUID.randomUUID(), name);
    }

    public Group withName(String newName) {
        return new Group(id, newName);
    }
}
