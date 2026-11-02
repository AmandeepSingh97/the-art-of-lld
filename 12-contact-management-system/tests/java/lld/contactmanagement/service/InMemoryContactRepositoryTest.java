package lld.contactmanagement.service;

import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.ContactChangeEvent;
import lld.contactmanagement.model.EmailAddress;
import lld.contactmanagement.model.EmailType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryContactRepositoryTest {

    private Contact sample(String name) {
        return Contact.builder()
                .displayName(name)
                .addEmail(EmailAddress.of(name.toLowerCase().replace(" ", ".") + "@example.com", EmailType.OTHER))
                .build();
    }

    @Test
    void saveThenFindByIdRoundTrips() {
        InMemoryContactRepository repository = new InMemoryContactRepository();
        Contact contact = sample("Alice");

        repository.save(contact);

        assertEquals(Optional.of(contact), repository.findById(contact.id()));
    }

    @Test
    void compareAndSetSucceedsOnlyAgainstTheExactReferenceLastWritten() {
        InMemoryContactRepository repository = new InMemoryContactRepository();
        Contact original = sample("Bob");
        repository.save(original);

        Contact updated = original.toBuilder().notes("updated").build();
        assertTrue(repository.compareAndSet(original.id(), original, updated));
        assertEquals(Optional.of(updated), repository.findById(original.id()));
    }

    @Test
    void compareAndSetFailsIfExpectedReferenceIsStale() {
        InMemoryContactRepository repository = new InMemoryContactRepository();
        Contact original = sample("Carl");
        repository.save(original);

        Contact firstUpdate = original.toBuilder().notes("first").build();
        assertTrue(repository.compareAndSet(original.id(), original, firstUpdate));

        // 'original' is now stale - a second CAS against it must fail even
        // though original.equals(firstUpdate) is true (id-based equality),
        // which is exactly the scenario InMemoryContactRepository's javadoc
        // explains Map#replace(key, oldValue, newValue) would get wrong.
        Contact secondUpdate = original.toBuilder().notes("second").build();
        assertFalse(repository.compareAndSet(original.id(), original, secondUpdate),
                "CAS must use reference identity, not id-based equals, or this lost-update would go undetected");
        assertEquals(Optional.of(firstUpdate), repository.findById(original.id()));
    }

    @Test
    void compareAndSetOnMissingIdReturnsFalseWithoutThrowing() {
        InMemoryContactRepository repository = new InMemoryContactRepository();
        Contact phantom = sample("Dana");

        assertFalse(repository.compareAndSet(phantom.id(), phantom, phantom));
    }

    @Test
    void deleteRemovesAndReturnsThePriorValue() {
        InMemoryContactRepository repository = new InMemoryContactRepository();
        Contact contact = sample("Eve");
        repository.save(contact);

        Optional<Contact> deleted = repository.delete(contact.id());

        assertEquals(Optional.of(contact), deleted);
        assertTrue(repository.findById(contact.id()).isEmpty());
    }

    @Test
    void listenersReceiveCreatedUpdatedAndDeletedEvents() {
        InMemoryContactRepository repository = new InMemoryContactRepository();
        List<ContactChangeEvent.EventType> seen = new ArrayList<>();
        repository.addChangeListener(event -> seen.add(event.type()));

        Contact contact = sample("Frank");
        repository.save(contact);
        Contact updated = contact.toBuilder().notes("x").build();
        repository.compareAndSet(contact.id(), contact, updated);
        repository.delete(contact.id());

        assertEquals(
                List.of(
                        ContactChangeEvent.EventType.CREATED,
                        ContactChangeEvent.EventType.UPDATED,
                        ContactChangeEvent.EventType.DELETED),
                seen);
    }
}
