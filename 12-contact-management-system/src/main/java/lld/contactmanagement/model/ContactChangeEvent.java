package lld.contactmanagement.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Event published by the repository whenever a contact's persisted state
 * changes. Consumed by {@code IndexManager} (Observer pattern) to keep every
 * index in sync without the repository or service layer knowing indexes
 * exist.
 *
 * @param type   what happened
 * @param before state prior to the change ({@code null} for CREATED)
 * @param after  state after the change ({@code null} for DELETED)
 */
public record ContactChangeEvent(EventType type, Contact before, Contact after) {

    public enum EventType {
        CREATED,
        UPDATED,
        DELETED
    }

    public ContactChangeEvent {
        Objects.requireNonNull(type, "type must not be null");
        if (type == EventType.CREATED && after == null) {
            throw new IllegalArgumentException("CREATED event requires 'after'");
        }
        if (type == EventType.DELETED && before == null) {
            throw new IllegalArgumentException("DELETED event requires 'before'");
        }
        if (type == EventType.UPDATED && (before == null || after == null)) {
            throw new IllegalArgumentException("UPDATED event requires both 'before' and 'after'");
        }
    }

    public static ContactChangeEvent created(Contact after) {
        return new ContactChangeEvent(EventType.CREATED, null, after);
    }

    public static ContactChangeEvent updated(Contact before, Contact after) {
        return new ContactChangeEvent(EventType.UPDATED, before, after);
    }

    public static ContactChangeEvent deleted(Contact before) {
        return new ContactChangeEvent(EventType.DELETED, before, null);
    }

    public Optional<Contact> beforeOpt() {
        return Optional.ofNullable(before);
    }

    public Optional<Contact> afterOpt() {
        return Optional.ofNullable(after);
    }
}
