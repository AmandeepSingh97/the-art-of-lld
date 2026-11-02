package lld.contactmanagement.service;

import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.ContactChangeEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory {@link ContactRepository} backed by {@link ConcurrentHashMap}.
 *
 * <p><b>Why {@link #compareAndSet} does NOT use
 * {@code ConcurrentHashMap#replace(Object, Object, Object)}.</b> That JDK
 * method succeeds when the currently-mapped value is <em>equal to</em> (via
 * {@code .equals()}) the expected value - not merely reference-identical.
 * {@code Contact.equals} is intentionally id-based entity equality (see
 * {@code Contact} javadoc), so two {@code Contact} instances for the same
 * id but with completely different field values (phones, favorite flag,
 * etc.) are {@code .equals()}-equal. If we used {@code map.replace(id,
 * expected, updated)} directly, a CAS could spuriously "succeed" against a
 * value that changed since we read {@code expected}, silently discarding a
 * concurrent writer's update (a lost update) - exactly the bug optimistic
 * concurrency control exists to prevent.
 *
 * <p>Instead, {@link #compareAndSet} uses
 * {@link ConcurrentHashMap#computeIfPresent} and compares the currently
 * mapped value to {@code expected} with reference identity ({@code ==}).
 * Because {@code Contact} is immutable and every edit produces a brand new
 * instance (see {@code Contact} javadoc), reference identity exactly
 * captures "nobody has swapped this contact out from under me since I read
 * it" - the precise guarantee a CAS loop needs. The remapping function
 * passed to {@code computeIfPresent} executes atomically with respect to
 * other operations on the same key (the JDK guarantees this), so the
 * identity check and the swap happen as one indivisible step with no
 * additional locking required.
 */
public final class InMemoryContactRepository implements ContactRepository {

    private final ConcurrentHashMap<UUID, Contact> store = new ConcurrentHashMap<>();
    private final List<ContactChangeListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void save(Contact contact) {
        Contact previous = store.put(contact.id(), contact);
        if (previous == null) {
            notifyListeners(ContactChangeEvent.created(contact));
        } else {
            notifyListeners(ContactChangeEvent.updated(previous, contact));
        }
    }

    @Override
    public Optional<Contact> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Collection<Contact> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public Optional<Contact> delete(UUID id) {
        Contact removed = store.remove(id);
        if (removed != null) {
            notifyListeners(ContactChangeEvent.deleted(removed));
        }
        return Optional.ofNullable(removed);
    }

    @Override
    public boolean compareAndSet(UUID id, Contact expected, Contact updated) {
        AtomicBoolean success = new AtomicBoolean(false);
        store.computeIfPresent(id, (key, current) -> {
            if (current == expected) { // reference identity - see class javadoc
                success.set(true);
                return updated;
            }
            return current; // no change; caller's expected snapshot is stale
        });
        if (success.get()) {
            notifyListeners(ContactChangeEvent.updated(expected, updated));
        }
        return success.get();
    }

    @Override
    public void addChangeListener(ContactChangeListener listener) {
        listeners.add(listener);
    }

    private void notifyListeners(ContactChangeEvent event) {
        // CopyOnWriteArrayList gives a consistent, lock-free snapshot of
        // listeners to iterate even if a listener is registered concurrently.
        for (ContactChangeListener listener : new ArrayList<>(listeners)) {
            listener.onChange(event);
        }
    }
}
