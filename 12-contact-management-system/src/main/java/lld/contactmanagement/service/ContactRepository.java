package lld.contactmanagement.service;

import lld.contactmanagement.model.Contact;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage abstraction for {@link Contact} aggregates. Defined as an
 * interface (Dependency Inversion) so {@code ContactService} depends on this
 * contract, not on {@link InMemoryContactRepository} - a persistent
 * implementation (SQL/NoSQL-backed) could be swapped in without touching
 * domain/service logic. See README section 12 for why in-memory is the
 * right default for this problem scope, and what a persistent
 * implementation would need to preserve (notably the {@link #compareAndSet}
 * contract).
 */
public interface ContactRepository {

    /** Unconditional upsert; used for inserts and for restoring a contact (merge-undo). */
    void save(Contact contact);

    Optional<Contact> findById(UUID id);

    Collection<Contact> findAll();

    /** Removes the contact if present; returns it, or empty if it wasn't present. */
    Optional<Contact> delete(UUID id);

    /**
     * Atomically replaces the stored value for {@code id} with
     * {@code updated} only if the currently-stored instance is the exact
     * same object reference as {@code expected} - i.e. true optimistic
     * concurrency control (OCC) using reference identity, not
     * {@link Object#equals}. See {@link InMemoryContactRepository} javadoc
     * for why reference identity (not equals) is required for correctness
     * here, given that {@code Contact.equals} is entity-identity (id-based).
     *
     * @return true if the swap happened, false if another writer won the race
     */
    boolean compareAndSet(UUID id, Contact expected, Contact updated);

    void addChangeListener(ContactChangeListener listener);
}
