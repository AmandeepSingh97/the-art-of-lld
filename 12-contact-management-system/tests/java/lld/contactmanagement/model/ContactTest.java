package lld.contactmanagement.model;

import lld.contactmanagement.exception.InvalidContactException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactTest {

    @Test
    void buildFailsOnBlankDisplayName() {
        assertThrows(InvalidContactException.class, () -> Contact.builder().displayName("   ").build());
    }

    @Test
    void duplicatePhonesWithDifferentFormattingAreDeduped() {
        Contact contact = Contact.builder()
                .displayName("Alice")
                .addPhone(PhoneNumber.of("(555) 123-4567", PhoneType.MOBILE))
                .addPhone(PhoneNumber.of("555-123-4567", PhoneType.MOBILE)) // same number, different formatting
                .build();

        assertEquals(1, contact.phoneNumbers().size(),
                "Phones that normalize to the same digits + type must collapse to one entry");
    }

    @Test
    void samePhoneDigitsWithDifferentTypesAreKeptSeparate() {
        Contact contact = Contact.builder()
                .displayName("Alice")
                .addPhone(PhoneNumber.of("555-123-4567", PhoneType.MOBILE))
                .addPhone(PhoneNumber.of("555-123-4567", PhoneType.WORK))
                .build();

        assertEquals(2, contact.phoneNumbers().size(),
                "Same number under different types (e.g. shared mobile/work line) must not be deduped");
    }

    @Test
    void equalsAndHashCodeAreIdentityBasedOnId() {
        Contact a = Contact.builder().displayName("Alice").addEmail(EmailAddress.of("a@x.com", EmailType.OTHER)).build();
        Contact b = a.toBuilder().displayName("Alice Updated").build();

        assertEquals(a, b, "Two snapshots of the same entity id must be .equals()");
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a.displayName(), b.displayName(), "but their field state legitimately differs");
    }

    @Test
    void toBuilderPreservesIdAndCreatedAtByDefault() {
        Contact original = Contact.builder()
                .displayName("Bob")
                .addEmail(EmailAddress.of("bob@x.com", EmailType.WORK))
                .build();
        Contact edited = original.toBuilder().notes("new note").build();

        assertEquals(original.id(), edited.id());
        assertEquals(original.createdAt(), edited.createdAt());
        assertEquals("new note", edited.notes());
    }

    @Test
    void nameTokensSplitsAndNormalizesDisplayName() {
        Contact contact = Contact.builder()
                .displayName("  John   SMITH ")
                .addEmail(EmailAddress.of("j@x.com", EmailType.OTHER))
                .build();

        assertEquals(List.of("john", "smith"), List.of(contact.nameTokens()));
    }

    @Test
    void hasPhoneAndHasEmailUseNormalizedComparison() {
        Contact contact = Contact.builder()
                .displayName("Carl")
                .addPhone(PhoneNumber.of("(555) 000-1111", PhoneType.HOME))
                .addEmail(EmailAddress.of("CARL@Example.com", EmailType.PERSONAL))
                .build();

        assertTrue(contact.hasPhone("5550001111"));
        assertTrue(contact.hasEmail("carl@example.com"));
        assertFalse(contact.hasPhone("5559999999"));
    }

    @Test
    void groupIdsAreImmutableFromOutside() {
        UUID groupId = UUID.randomUUID();
        Contact contact = Contact.builder()
                .displayName("Dana")
                .addEmail(EmailAddress.of("d@x.com", EmailType.OTHER))
                .addGroup(groupId)
                .build();

        assertThrows(UnsupportedOperationException.class, () -> contact.groupIds().add(UUID.randomUUID()));
    }
}
