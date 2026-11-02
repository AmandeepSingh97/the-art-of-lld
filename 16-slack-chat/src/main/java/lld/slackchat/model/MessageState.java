package lld.slackchat.model;

/**
 * Lifecycle state of a {@link Message}.
 *
 * <p>Legal transitions: {@code ACTIVE -> EDITED} (repeatable), {@code ACTIVE -> DELETED},
 * {@code EDITED -> DELETED}. {@code DELETED} is terminal: a deleted message can never be
 * edited or un-deleted (soft-delete with a tombstone, not a hard delete -- see README
 * section 12 for why we keep the tombstone instead of removing the row).
 */
public enum MessageState {
    ACTIVE,
    EDITED,
    DELETED
}
