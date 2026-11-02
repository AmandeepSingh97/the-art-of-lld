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

class DuplicateDetectionServiceTest {

    private ContactManagementSystem system;

    @BeforeEach
    void setUp() {
        system = new ContactManagementSystem(new TrieNameIndex());
    }

    @Test
    void findsDuplicateBySharedNormalizedPhoneDespiteDifferentFormatting() {
        Contact original = system.contacts().create(Contact.builder()
                .displayName("Maya Patel")
                .addPhone(PhoneNumber.of("(555) 222-3333", PhoneType.MOBILE)));
        Contact reEntered = system.contacts().create(Contact.builder()
                .displayName("M. Patel")
                .addPhone(PhoneNumber.of("555.222.3333", PhoneType.MOBILE)));

        List<Contact> duplicates = system.duplicates().findPotentialDuplicates(original);

        assertEquals(List.of(reEntered), duplicates);
    }

    @Test
    void findsDuplicateBySharedNormalizedEmail() {
        Contact original = system.contacts().create(Contact.builder()
                .displayName("Noah Lee")
                .addEmail(EmailAddress.of("noah.lee@example.com", EmailType.PERSONAL)));
        Contact reEntered = system.contacts().create(Contact.builder()
                .displayName("Noah L.")
                .addEmail(EmailAddress.of("Noah.Lee@Example.com", EmailType.PERSONAL)));

        List<Contact> duplicates = system.duplicates().findPotentialDuplicates(original);

        assertEquals(List.of(reEntered), duplicates);
    }

    @Test
    void findsDuplicateByExactNormalizedNameMatch() {
        Contact original = system.contacts().create(Contact.builder()
                .displayName("Oscar Diaz")
                .addEmail(EmailAddress.of("oscar1@example.com", EmailType.OTHER)));
        Contact sameNameDifferentContact = system.contacts().create(Contact.builder()
                .displayName("oscar   diaz")
                .addEmail(EmailAddress.of("oscar2@example.com", EmailType.OTHER)));

        List<Contact> duplicates = system.duplicates().findPotentialDuplicates(original);

        assertEquals(List.of(sameNameDifferentContact), duplicates);
    }

    @Test
    void unrelatedContactsAreNotFlaggedAsDuplicates() {
        Contact a = system.contacts().create(Contact.builder()
                .displayName("Priya Shah")
                .addEmail(EmailAddress.of("priya@example.com", EmailType.OTHER)));
        system.contacts().create(Contact.builder()
                .displayName("Quentin Reed")
                .addEmail(EmailAddress.of("quentin@example.com", EmailType.OTHER)));

        assertTrue(system.duplicates().findPotentialDuplicates(a).isEmpty());
    }

    @Test
    void candidateGenerationExcludesTheTargetItself() {
        Contact onlyContact = system.contacts().create(Contact.builder()
                .displayName("Solo Person")
                .addEmail(EmailAddress.of("solo@example.com", EmailType.OTHER)));

        assertTrue(system.duplicates().findPotentialDuplicates(onlyContact).isEmpty());
    }
}
