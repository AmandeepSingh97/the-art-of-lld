package lld.contactmanagement.index;

import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.ContactChangeEvent;
import lld.contactmanagement.model.EmailAddress;
import lld.contactmanagement.model.EmailType;
import lld.contactmanagement.model.PhoneNumber;
import lld.contactmanagement.model.PhoneType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexManagerTest {

    private IndexManager indexManager;

    @BeforeEach
    void setUp() {
        indexManager = new IndexManager(new TrieNameIndex());
    }

    private Contact sample(String name, String phone, String email) {
        Contact.Builder builder = Contact.builder().displayName(name);
        if (phone != null) {
            builder.addPhone(PhoneNumber.of(phone, PhoneType.MOBILE));
        }
        if (email != null) {
            builder.addEmail(EmailAddress.of(email, EmailType.OTHER));
        }
        return builder.build();
    }

    @Test
    void createdEventIndexesNamePhoneAndEmail() {
        Contact contact = sample("John Smith", "555-111-2222", "john@example.com");

        indexManager.onChange(ContactChangeEvent.created(contact));

        assertEquals(Set.of(contact.id()), indexManager.searchByNamePrefix("smi"));
        assertEquals(Set.of(contact.id()), indexManager.findByNormalizedPhone("5551112222"));
        assertEquals(Set.of(contact.id()), indexManager.findByNormalizedEmail("john@example.com"));
    }

    @Test
    void deletedEventRemovesFromEveryIndex() {
        Contact contact = sample("John Smith", "555-111-2222", "john@example.com");
        indexManager.onChange(ContactChangeEvent.created(contact));

        indexManager.onChange(ContactChangeEvent.deleted(contact));

        assertTrue(indexManager.searchByNamePrefix("smi").isEmpty());
        assertTrue(indexManager.findByNormalizedPhone("5551112222").isEmpty());
        assertTrue(indexManager.findByNormalizedEmail("john@example.com").isEmpty());
    }

    @Test
    void updatedEventReindexesChangedFieldsOnly() {
        Contact original = sample("John Smith", "555-111-2222", "john@example.com");
        indexManager.onChange(ContactChangeEvent.created(original));

        Contact renamed = original.toBuilder().displayName("Johnny Appleseed").build();
        indexManager.onChange(ContactChangeEvent.updated(original, renamed));

        assertTrue(indexManager.searchByNamePrefix("smi").isEmpty(), "old name must be de-indexed");
        assertEquals(Set.of(renamed.id()), indexManager.searchByNamePrefix("johnny"));
        // phone/email were untouched by the rename but must still resolve post-update
        assertEquals(Set.of(renamed.id()), indexManager.findByNormalizedPhone("5551112222"));
    }

    @Test
    void favoriteFlagIsTrackedAndClearedOnDeindex() {
        Contact favorite = sample("Fave Person", null, "fave@example.com").toBuilder().favorite(true).build();
        indexManager.onChange(ContactChangeEvent.created(favorite));

        assertEquals(Set.of(favorite.id()), indexManager.favoriteIds());

        indexManager.onChange(ContactChangeEvent.deleted(favorite));
        assertTrue(indexManager.favoriteIds().isEmpty());
    }

    @Test
    void groupMembershipReverseIndexTracksAddAndRemove() {
        UUID groupId = UUID.randomUUID();
        Contact member = sample("Group Member", null, "gm@example.com").toBuilder().addGroup(groupId).build();

        indexManager.onChange(ContactChangeEvent.created(member));
        assertEquals(Set.of(member.id()), indexManager.groupMemberIds(groupId));

        Contact removedFromGroup = member.toBuilder().removeGroup(groupId).build();
        indexManager.onChange(ContactChangeEvent.updated(member, removedFromGroup));
        assertTrue(indexManager.groupMemberIds(groupId).isEmpty());
    }
}
