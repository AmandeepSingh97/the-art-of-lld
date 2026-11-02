package lld.contactmanagement.service;

import lld.contactmanagement.index.SortedArrayNameIndex;
import lld.contactmanagement.index.TrieNameIndex;
import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.EmailAddress;
import lld.contactmanagement.model.EmailType;
import lld.contactmanagement.model.PhoneNumber;
import lld.contactmanagement.model.PhoneType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchServiceTest {

    static Stream<ContactManagementSystem> systems() {
        return Stream.of(
                new ContactManagementSystem(new TrieNameIndex()),
                new ContactManagementSystem(new SortedArrayNameIndex()));
    }

    @ParameterizedTest
    @MethodSource("systems")
    void searchByNamePrefixReturnsAlphabeticallySortedMatches(ContactManagementSystem system) {
        system.contacts().create(Contact.builder().displayName("Charlie Smith")
                .addEmail(EmailAddress.of("c@example.com", EmailType.OTHER)));
        system.contacts().create(Contact.builder().displayName("Adam Smith")
                .addEmail(EmailAddress.of("a@example.com", EmailType.OTHER)));
        system.contacts().create(Contact.builder().displayName("Bella Smith")
                .addEmail(EmailAddress.of("b@example.com", EmailType.OTHER)));

        List<Contact> results = system.search().searchByNamePrefix("smi", 10);

        assertEquals(List.of("Adam Smith", "Bella Smith", "Charlie Smith"),
                results.stream().map(Contact::displayName).toList());
    }

    @ParameterizedTest
    @MethodSource("systems")
    void searchByNamePrefixRespectsLimit(ContactManagementSystem system) {
        for (int i = 0; i < 5; i++) {
            system.contacts().create(Contact.builder().displayName("Person" + i)
                    .addEmail(EmailAddress.of("p" + i + "@example.com", EmailType.OTHER)));
        }

        assertEquals(2, system.search().searchByNamePrefix("person", 2).size());
    }

    @Test
    void searchByPhoneFindsExactNormalizedMatch() {
        ContactManagementSystem system = new ContactManagementSystem(new TrieNameIndex());
        Contact contact = system.contacts().create(Contact.builder()
                .displayName("Diego")
                .addPhone(PhoneNumber.of("(555) 777-8888", PhoneType.MOBILE)));

        List<Contact> results = system.search().searchByPhone("555.777.8888");

        assertEquals(List.of(contact), results);
    }

    @Test
    void searchByEmailFindsExactMatch() {
        ContactManagementSystem system = new ContactManagementSystem(new TrieNameIndex());
        Contact contact = system.contacts().create(Contact.builder()
                .displayName("Elena")
                .addEmail(EmailAddress.of("elena@example.com", EmailType.PERSONAL)));

        List<Contact> results = system.search().searchByEmail("ELENA@example.com");

        assertEquals(List.of(contact), results);
    }

    @Test
    void listFavoritesReturnsOnlyFavoritedContacts() {
        ContactManagementSystem system = new ContactManagementSystem(new TrieNameIndex());
        Contact fav = system.contacts().create(Contact.builder()
                .displayName("Fav Person")
                .addEmail(EmailAddress.of("fav@example.com", EmailType.OTHER)));
        system.contacts().create(Contact.builder()
                .displayName("Not Fav")
                .addEmail(EmailAddress.of("notfav@example.com", EmailType.OTHER)));

        system.contacts().setFavorite(fav.id(), true);

        List<Contact> favorites = system.search().listFavorites();
        assertEquals(1, favorites.size());
        assertTrue(favorites.get(0).favorite());
    }
}
