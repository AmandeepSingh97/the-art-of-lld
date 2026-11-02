package lld.contactmanagement.service;

import lld.contactmanagement.exception.GroupNotFoundException;
import lld.contactmanagement.index.TrieNameIndex;
import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.EmailAddress;
import lld.contactmanagement.model.EmailType;
import lld.contactmanagement.model.Group;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupServiceTest {

    private ContactManagementSystem system;

    @BeforeEach
    void setUp() {
        system = new ContactManagementSystem(new TrieNameIndex());
    }

    @Test
    void deletingAGroupCascadesMembershipRemovalFromEveryMember() {
        Group group = system.groups().createGroup("Book Club");
        Contact alice = system.contacts().create(Contact.builder()
                .displayName("Alice")
                .addEmail(EmailAddress.of("alice@example.com", EmailType.OTHER)));
        Contact bob = system.contacts().create(Contact.builder()
                .displayName("Bob")
                .addEmail(EmailAddress.of("bob@example.com", EmailType.OTHER)));
        system.contacts().addToGroup(alice.id(), group.id());
        system.contacts().addToGroup(bob.id(), group.id());

        system.groups().deleteGroup(group.id());

        assertFalse(system.contacts().get(alice.id()).orElseThrow().groupIds().contains(group.id()));
        assertFalse(system.contacts().get(bob.id()).orElseThrow().groupIds().contains(group.id()));
        assertTrue(system.groups().get(group.id()).isEmpty());
    }

    @Test
    void deletingAnUnknownGroupThrows() {
        assertThrows(GroupNotFoundException.class, () -> system.groups().deleteGroup(java.util.UUID.randomUUID()));
    }

    @Test
    void findOrCreateByNameIsIdempotent() {
        Group first = system.groups().findOrCreateByName("Vendors");
        Group second = system.groups().findOrCreateByName("Vendors");

        org.junit.jupiter.api.Assertions.assertEquals(first.id(), second.id());
    }

    @Test
    void renameGroupPreservesIdentity() {
        Group group = system.groups().createGroup("Old Name");
        Group renamed = system.groups().renameGroup(group.id(), "New Name");

        org.junit.jupiter.api.Assertions.assertEquals(group.id(), renamed.id());
        org.junit.jupiter.api.Assertions.assertEquals("New Name", renamed.name());
    }
}
