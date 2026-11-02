package lld.contactmanagement.service;

import lld.contactmanagement.exception.ContactNotFoundException;
import lld.contactmanagement.exception.GroupNotFoundException;
import lld.contactmanagement.exception.InvalidContactException;
import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.EmailAddress;
import lld.contactmanagement.model.EmailType;
import lld.contactmanagement.model.Group;
import lld.contactmanagement.model.PhoneNumber;
import lld.contactmanagement.model.PhoneType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactServiceTest {

    private ContactRepository repository;
    private GroupRepository groupRepository;
    private ContactService contactService;

    @BeforeEach
    void setUp() {
        repository = new InMemoryContactRepository();
        groupRepository = new InMemoryGroupRepository();
        contactService = new ContactService(repository, groupRepository, new HistoryService());
    }

    @Test
    void createRejectsContactWithNoPhoneOrEmail() {
        assertThrows(InvalidContactException.class,
                () -> contactService.create(Contact.builder().displayName("No Contact Points")));
    }

    @Test
    void createPersistsAContactWithAtLeastOneContactPoint() {
        Contact contact = contactService.create(Contact.builder()
                .displayName("Alice")
                .addEmail(EmailAddress.of("alice@example.com", EmailType.PERSONAL)));

        assertEquals(Optional.of(contact), contactService.get(contact.id()));
    }

    @Test
    void updateThrowsContactNotFoundForUnknownId() {
        assertThrows(ContactNotFoundException.class,
                () -> contactService.update(UUID.randomUUID(), b -> b.notes("x")));
    }

    @Test
    void updateAppliesMutatorAndBumpsUpdatedAt() throws InterruptedException {
        Contact contact = contactService.create(Contact.builder()
                .displayName("Bob")
                .addEmail(EmailAddress.of("bob@example.com", EmailType.WORK)));

        Thread.sleep(2); // ensure a measurable clock tick between createdAt and updatedAt
        Contact updated = contactService.update(contact.id(), b -> b.notes("called back"));

        assertEquals("called back", updated.notes());
        assertTrue(updated.updatedAt().isAfter(contact.updatedAt()));
    }

    @Test
    void addToGroupRequiresAnExistingGroup() {
        Contact contact = contactService.create(Contact.builder()
                .displayName("Carl")
                .addEmail(EmailAddress.of("carl@example.com", EmailType.OTHER)));

        assertThrows(GroupNotFoundException.class,
                () -> contactService.addToGroup(contact.id(), UUID.randomUUID()));
    }

    @Test
    void addToGroupSucceedsForAnExistingGroup() {
        Group group = Group.create("Friends");
        groupRepository.save(group);
        Contact contact = contactService.create(Contact.builder()
                .displayName("Dana")
                .addEmail(EmailAddress.of("dana@example.com", EmailType.OTHER)));

        Contact updated = contactService.addToGroup(contact.id(), group.id());

        assertTrue(updated.groupIds().contains(group.id()));
    }

    @Test
    void undoRestoresThePreviousSnapshot() {
        Contact original = contactService.create(Contact.builder()
                .displayName("Eve")
                .addEmail(EmailAddress.of("eve@example.com", EmailType.PERSONAL)));

        contactService.update(original.id(), b -> b.notes("edited"));
        Contact undone = contactService.undo(original.id()).orElseThrow();

        assertEquals("", undone.notes());
    }

    @Test
    void undoOnContactWithNoHistoryReturnsEmpty() {
        Contact contact = contactService.create(Contact.builder()
                .displayName("Frank")
                .addEmail(EmailAddress.of("frank@example.com", EmailType.OTHER)));

        assertTrue(contactService.undo(contact.id()).isEmpty());
    }

    @Test
    void deleteForgetsHistorySoUndoAfterDeleteReturnsEmpty() {
        Contact contact = contactService.create(Contact.builder()
                .displayName("Grace")
                .addEmail(EmailAddress.of("grace@example.com", EmailType.OTHER)));
        contactService.update(contact.id(), b -> b.notes("edited"));

        contactService.delete(contact.id());

        assertTrue(contactService.undo(contact.id()).isEmpty());
        assertThrows(ContactNotFoundException.class, () -> contactService.delete(contact.id()));
    }

    @Test
    void mergeContactsUnionsPhonesEmailsAndGroupsAndDeletesTheDuplicate() {
        Group group = Group.create("Work");
        groupRepository.save(group);

        Contact primary = contactService.create(Contact.builder()
                .displayName("Henry")
                .addPhone(PhoneNumber.of("555-111-0000", PhoneType.MOBILE)));
        Contact duplicate = contactService.create(Contact.builder()
                .displayName("Henry")
                .addEmail(EmailAddress.of("henry@example.com", EmailType.WORK))
                .favorite(true));
        contactService.addToGroup(duplicate.id(), group.id());
        duplicate = contactService.get(duplicate.id()).orElseThrow();

        ContactService.MergeResult result = contactService.mergeContacts(primary.id(), duplicate.id());

        Contact merged = result.merged();
        assertEquals(primary.id(), merged.id());
        assertTrue(merged.hasPhone("5551110000"));
        assertTrue(merged.hasEmail("henry@example.com"));
        assertTrue(merged.groupIds().contains(group.id()));
        assertTrue(merged.favorite());
        assertTrue(contactService.get(duplicate.id()).isEmpty(), "duplicate must be deleted after merge");
    }

    @Test
    void undoMergeRecreatesTheDeletedDuplicateAndRestoresThePreMergeKeepState() {
        Contact primary = contactService.create(Contact.builder()
                .displayName("Ivy")
                .addPhone(PhoneNumber.of("555-222-0000", PhoneType.MOBILE)));
        Contact duplicate = contactService.create(Contact.builder()
                .displayName("Ivy")
                .addEmail(EmailAddress.of("ivy@example.com", EmailType.OTHER)));

        ContactService.MergeResult result = contactService.mergeContacts(primary.id(), duplicate.id());
        contactService.undoMerge(result);

        Contact restoredPrimary = contactService.get(primary.id()).orElseThrow();
        assertFalse(restoredPrimary.hasEmail("ivy@example.com"), "pre-merge keep state must not have the merged-in email");
        assertTrue(contactService.get(duplicate.id()).isPresent(), "duplicate must be recreated");
    }

    @Test
    void concurrentUpdatesToTheSameContactAreNotLost() throws InterruptedException {
        // Negative-control style test: each of N threads adds a distinct
        // phone number to the SAME contact concurrently. Without a correct
        // CAS retry loop in ContactService#update, interleaved read-modify-
        // write races would silently drop some of these additions (a lost
        // update). We assert all N phones survive.
        Contact contact = contactService.create(Contact.builder()
                .displayName("Jack")
                .addEmail(EmailAddress.of("jack@example.com", EmailType.OTHER)));

        int threadCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threadCount; i++) {
                int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    PhoneNumber phone = PhoneNumber.of("555-000-" + String.format("%04d", index), PhoneType.OTHER);
                    contactService.addPhone(contact.id(), phone);
                });
            }
            ready.await();
            start.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        Contact finalContact = contactService.get(contact.id()).orElseThrow();
        assertEquals(threadCount, finalContact.phoneNumbers().size(),
                "every concurrently-added phone number must survive the CAS retry loop");
    }

    @Test
    void removePhoneRemovesByNormalizedValueRegardlessOfOriginalFormatting() {
        Contact contact = contactService.create(Contact.builder()
                .displayName("Kim")
                .addPhone(PhoneNumber.of("(555) 333-4444", PhoneType.HOME))
                .addEmail(EmailAddress.of("kim@example.com", EmailType.OTHER)));

        Contact updated = contactService.removePhone(contact.id(), "5553334444");

        assertTrue(updated.phoneNumbers().isEmpty());
    }

    @Test
    void removingTheLastContactPointIsRejectedByTheBusinessInvariant() {
        Contact contact = contactService.create(Contact.builder()
                .displayName("Nora")
                .addPhone(PhoneNumber.of("555-444-5555", PhoneType.MOBILE)));

        assertThrows(InvalidContactException.class,
                () -> contactService.removePhone(contact.id(), "5554445555"));
    }

    @Test
    void settingFavoriteIsIdempotentAndObservableImmediately() {
        Contact contact = contactService.create(Contact.builder()
                .displayName("Liam")
                .addEmail(EmailAddress.of("liam@example.com", EmailType.OTHER)));

        Contact favored = contactService.setFavorite(contact.id(), true);
        assertTrue(favored.favorite());

        Contact favoredAgain = contactService.setFavorite(contact.id(), true);
        assertTrue(favoredAgain.favorite());
    }
}
