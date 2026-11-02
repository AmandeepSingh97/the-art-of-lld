package lld.contactmanagement.service;

import lld.contactmanagement.index.TrieNameIndex;
import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.EmailAddress;
import lld.contactmanagement.model.EmailType;
import lld.contactmanagement.model.PhoneNumber;
import lld.contactmanagement.model.PhoneType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportExportServiceTest {

    private ContactManagementSystem system;

    @BeforeEach
    void setUp() {
        system = new ContactManagementSystem(new TrieNameIndex());
    }

    @Test
    void exportThenImportRoundTripsCoreFields() {
        Contact original = system.contacts().create(Contact.builder()
                .displayName("Priya Shah")
                .addPhone(PhoneNumber.of("(555) 444-5555", PhoneType.MOBILE))
                .addEmail(EmailAddress.of("priya@example.com", EmailType.WORK))
                .favorite(true)
                .notes("met at conference"));

        String vCard = system.importExport().exportContact(original);
        Contact reimported = system.importExport().importContact(vCard);

        assertEquals(original.displayName(), reimported.displayName());
        assertTrue(reimported.hasPhone("5554445555"));
        assertTrue(reimported.hasEmail("priya@example.com"));
        assertTrue(reimported.favorite());
        assertEquals("met at conference", reimported.notes());
    }

    @Test
    void exportResolvesGroupIdsToNamesAndImportRecreatesOrReusesGroupsByName() {
        var group = system.groups().createGroup("Investors");
        Contact original = system.contacts().create(Contact.builder()
                .displayName("Raj Kumar")
                .addEmail(EmailAddress.of("raj@example.com", EmailType.WORK)));
        system.contacts().addToGroup(original.id(), group.id());
        original = system.contacts().get(original.id()).orElseThrow();

        String vCard = system.importExport().exportContact(original);
        assertTrue(vCard.contains("CATEGORIES:Investors"));

        Contact reimported = system.importExport().importContact(vCard);

        assertEquals(1, reimported.groupIds().size());
        var resolvedGroup = system.groups().get(reimported.groupIds().iterator().next()).orElseThrow();
        assertEquals("Investors", resolvedGroup.name());
        assertEquals(group.id(), resolvedGroup.id(), "existing group must be reused by name, not duplicated");
    }

    @Test
    void importAllParsesMultipleConcatenatedRecordsFromExportAll() {
        system.contacts().create(Contact.builder()
                .displayName("Sam A")
                .addEmail(EmailAddress.of("sama@example.com", EmailType.OTHER)));
        system.contacts().create(Contact.builder()
                .displayName("Sam B")
                .addEmail(EmailAddress.of("samb@example.com", EmailType.OTHER)));

        String exported = system.importExport().exportAll();

        ContactManagementSystem freshSystem = new ContactManagementSystem(new TrieNameIndex());
        List<Contact> imported = freshSystem.importExport().importAll(exported);

        assertEquals(2, imported.size());
        assertEquals(2, freshSystem.contacts().getAll().size());
    }
}
