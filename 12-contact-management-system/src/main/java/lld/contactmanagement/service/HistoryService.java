package lld.contactmanagement.service;

import lld.contactmanagement.model.Contact;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Implements the undo capability using the Memento pattern.
 *
 * <p>Because {@link Contact} is already immutable (see its javadoc), the
 * "memento" IS simply the previous {@code Contact} instance - there is no
 * separate snapshot/state-capture type to maintain in lock-step with the
 * entity's fields. {@code ContactService} (the "originator"/"caretaker" in
 * classic Memento terminology) pushes the pre-edit snapshot here before
 * every mutating operation; this class is the "caretaker" storage, holding
 * snapshots opaquely without needing to understand {@code Contact}'s
 * internals.
 *
 * <p>History is capped per contact at {@value #MAX_HISTORY_PER_CONTACT}
 * entries to bound memory for long-lived, frequently-edited contacts. The
 * trim uses a plain size check after {@link Deque#addLast} on a
 * {@link ConcurrentLinkedDeque}: under concurrent edits to the <em>same</em>
 * contact (itself already serialized by {@code ContactService}'s per-contact
 * CAS loop + the fact a single logical edit = one push), the trim can,
 * extremely rarely, leave the deque one element above or below the exact
 * cap if interleaved with {@link #undo}. This is an accepted, benign
 * approximation for an undo-stack size bound - not a correctness property
 * any caller relies on.
 */
public final class HistoryService {

    private static final int MAX_HISTORY_PER_CONTACT = 20;

    private final ConcurrentHashMap<UUID, Deque<Contact>> history = new ConcurrentHashMap<>();

    /** Records {@code previousState} as the snapshot to restore to on the next undo. */
    public void record(UUID contactId, Contact previousState) {
        Deque<Contact> deque = history.computeIfAbsent(contactId, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(previousState);
        while (deque.size() > MAX_HISTORY_PER_CONTACT) {
            deque.pollFirst();
        }
    }

    /** Pops and returns the most recent snapshot, or empty if there is no history. */
    public Optional<Contact> undo(UUID contactId) {
        Deque<Contact> deque = history.get(contactId);
        if (deque == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(deque.pollLast());
    }

    /** Drops all history for a contact (called on permanent delete to avoid leaking memory). */
    public void forget(UUID contactId) {
        history.remove(contactId);
    }

    public int historySize(UUID contactId) {
        Deque<Contact> deque = history.get(contactId);
        return deque == null ? 0 : deque.size();
    }

    /** Exposes a defensive snapshot of the stack, most-recent-last, for diagnostics/tests. */
    public Deque<Contact> snapshot(UUID contactId) {
        Deque<Contact> deque = history.get(contactId);
        return deque == null ? new ArrayDeque<>() : new ArrayDeque<>(deque);
    }
}
