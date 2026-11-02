package lld.contactmanagement.service;

import lld.contactmanagement.exception.ContactNotFoundException;
import lld.contactmanagement.exception.GroupNotFoundException;
import lld.contactmanagement.exception.InvalidContactException;
import lld.contactmanagement.exception.OptimisticLockException;
import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.EmailAddress;
import lld.contactmanagement.model.PhoneNumber;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Core orchestration service for contact CRUD, favorites, group membership,
 * undo, and duplicate merging. This is the single entry point mutating
 * state - {@code IndexManager}, {@code SearchService}, and
 * {@code DuplicateDetectionService} only ever read.
 *
 * <p><b>Concurrency model.</b> Single-contact edits ({@link #update}, and
 * everything built on it: {@link #setFavorite}, {@link #addToGroup},
 * {@link #addPhone}, etc.) use a lock-free optimistic-concurrency (CAS)
 * retry loop against {@link ContactRepository#compareAndSet}: read the
 * current immutable snapshot, build a new snapshot, attempt an atomic swap,
 * retry on failure. This scales well because unrelated contacts never
 * contend (the CAS key is the contact id), and even same-contact
 * contention is rare in a personal/team address book workload.
 *
 * <p>{@link #mergeContacts} is the one operation that spans two aggregate
 * roots atomically from the caller's point of view (update one contact,
 * delete another). A per-service {@link ReentrantLock} serializes merges
 * against each other and against deletes, trading a small amount of
 * throughput for a simple, demonstrably-correct cross-aggregate invariant:
 * "the contact being merged away cannot simultaneously be merged into or
 * deleted by another thread". See README section 11 for the residual race
 * this does <em>not</em> close (a concurrent single-field {@link #update}
 * on the "keep" contact, which does not take this lock) and why that
 * trade-off is acceptable for this problem's scope.
 */
public final class ContactService {

    private static final int MAX_CAS_RETRIES = 100;

    private final ContactRepository repository;
    private final GroupRepository groupRepository;
    private final HistoryService historyService;
    private final ReentrantLock structuralLock = new ReentrantLock();

    public ContactService(
            ContactRepository repository,
            GroupRepository groupRepository,
            HistoryService historyService) {
        this.repository = repository;
        this.groupRepository = groupRepository;
        this.historyService = historyService;
    }

    /**
     * Creates and persists a new contact. Enforces the business-level
     * invariant - at least one phone or email - that {@code Contact.build()}
     * deliberately does not enforce itself (see {@code Contact} javadoc).
     */
    public Contact create(Contact.Builder builder) {
        Contact contact = builder.build();
        validateBusinessInvariants(contact);
        repository.save(contact);
        return contact;
    }

    public Optional<Contact> get(UUID id) {
        return repository.findById(id);
    }

    public Collection<Contact> getAll() {
        return repository.findAll();
    }

    /**
     * Applies {@code mutator} to a fresh builder from the current state and
     * atomically swaps the result in, retrying on concurrent interference.
     * The pre-edit snapshot is recorded with {@link HistoryService} only
     * after the swap succeeds, so a failed/retried attempt never pollutes
     * undo history with a snapshot nobody actually transitioned away from.
     */
    public Contact update(UUID id, Consumer<Contact.Builder> mutator) {
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            Contact current = repository.findById(id)
                    .orElseThrow(() -> new ContactNotFoundException(id));
            Contact.Builder builder = current.toBuilder();
            mutator.accept(builder);
            builder.updatedAt(Instant.now());
            Contact updated = builder.build();
            validateBusinessInvariants(updated);

            if (repository.compareAndSet(id, current, updated)) {
                historyService.record(id, current);
                return updated;
            }
            // Someone else swapped this contact between our read and our
            // write; loop and retry against the new current value.
        }
        throw new OptimisticLockException(id, MAX_CAS_RETRIES);
    }

    public void delete(UUID id) {
        repository.delete(id).orElseThrow(() -> new ContactNotFoundException(id));
        historyService.forget(id);
    }

    public Contact setFavorite(UUID id, boolean favorite) {
        return update(id, b -> b.favorite(favorite));
    }

    public Contact addPhone(UUID id, PhoneNumber phone) {
        return update(id, b -> b.addPhone(phone));
    }

    public Contact removePhone(UUID id, String normalizedPhone) {
        return update(id, b -> b.removePhone(normalizedPhone));
    }

    public Contact addEmail(UUID id, EmailAddress email) {
        return update(id, b -> b.addEmail(email));
    }

    public Contact removeEmail(UUID id, String normalizedEmail) {
        return update(id, b -> b.removeEmail(normalizedEmail));
    }

    public Contact addToGroup(UUID contactId, UUID groupId) {
        if (groupRepository.findById(groupId).isEmpty()) {
            throw new GroupNotFoundException(groupId);
        }
        return update(contactId, b -> b.addGroup(groupId));
    }

    public Contact removeFromGroup(UUID contactId, UUID groupId) {
        return update(contactId, b -> b.removeGroup(groupId));
    }

    /**
     * Restores a contact to the state immediately before its most recent
     * edit. Implemented as an unconditional save (not a CAS), because undo
     * is an explicit, user-initiated override of whatever the current state
     * is - the accepted semantics are last-writer-wins against any edit that
     * happened concurrently with the undo itself. This is documented, not
     * silently glossed over; see README section 11.
     */
    public Optional<Contact> undo(UUID id) {
        return historyService.undo(id).map(previous -> {
            Contact restored = previous.toBuilder().updatedAt(Instant.now()).build();
            repository.save(restored);
            return restored;
        });
    }

    /**
     * Merges {@code duplicateId} into {@code keepId}: field values are
     * unioned (see {@link ContactMerger}), {@code keepId} is updated in
     * place (preserving its identity), and {@code duplicateId} is deleted.
     * Returns a {@link MergeResult} capturing enough state to reverse the
     * operation via {@link #undoMerge}.
     */
    public MergeResult mergeContacts(UUID keepId, UUID duplicateId) {
        structuralLock.lock();
        try {
            Contact keep = repository.findById(keepId)
                    .orElseThrow(() -> new ContactNotFoundException(keepId));
            Contact duplicate = repository.findById(duplicateId)
                    .orElseThrow(() -> new ContactNotFoundException(duplicateId));

            Contact merged = ContactMerger.merge(keep, duplicate);
            validateBusinessInvariants(merged);

            if (!repository.compareAndSet(keepId, keep, merged)) {
                throw new OptimisticLockException(keepId, 1);
            }
            historyService.record(keepId, keep);
            repository.delete(duplicateId);

            return new MergeResult(merged, keep, duplicate);
        } finally {
            structuralLock.unlock();
        }
    }

    /** Reverses a merge: restores the pre-merge "keep" state and recreates the deleted contact. */
    public void undoMerge(MergeResult result) {
        structuralLock.lock();
        try {
            repository.save(result.preMergeKeepState());
            repository.save(result.removedContact());
        } finally {
            structuralLock.unlock();
        }
    }

    private void validateBusinessInvariants(Contact contact) {
        if (contact.phoneNumbers().isEmpty() && contact.emailAddresses().isEmpty()) {
            throw new InvalidContactException(
                    "Contact '" + contact.displayName() + "' must have at least one phone number or email address");
        }
    }

    /**
     * Result of a successful merge: the new merged state, the "keep"
     * contact's state immediately before the merge, and the full contact
     * that was deleted - everything {@link #undoMerge} needs to reverse the
     * operation exactly once.
     */
    public record MergeResult(Contact merged, Contact preMergeKeepState, Contact removedContact) {
    }
}
