package lld.contactmanagement.demo;

import lld.contactmanagement.index.TrieNameIndex;
import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.EmailAddress;
import lld.contactmanagement.model.EmailType;
import lld.contactmanagement.model.Group;
import lld.contactmanagement.model.PhoneNumber;
import lld.contactmanagement.model.PhoneType;
import lld.contactmanagement.service.ContactManagementSystem;
import lld.contactmanagement.service.ContactService;

import java.util.List;

/**
 * Runnable demonstration exercising every major capability: contact
 * creation with multiple phones/emails, group membership, prefix search,
 * favorites, edits + undo, duplicate detection + merge, and vCard-style
 * export/import.
 */
public final class Main {

    public static void main(String[] args) {
        ContactManagementSystem system = new ContactManagementSystem(new TrieNameIndex());
        ContactService contacts = system.contacts();

        System.out.println("=== 1. Creating contacts ===");
        Contact john = contacts.create(Contact.builder()
                .displayName("John Smith")
                .addPhone(PhoneNumber.of("(555) 123-4567", PhoneType.MOBILE))
                .addEmail(EmailAddress.of("john.smith@example.com", EmailType.PERSONAL)));
        System.out.println("Created: " + john);

        Contact jane = contacts.create(Contact.builder()
                .displayName("Jane Smythe")
                .addPhone(PhoneNumber.of("555-987-6543", PhoneType.WORK))
                .addEmail(EmailAddress.of("jane@example.com", EmailType.WORK)));
        System.out.println("Created: " + jane);

        // A near-duplicate of John, entered a second time with reformatted phone.
        Contact johnDuplicate = contacts.create(Contact.builder()
                .displayName("John Smith")
                .addPhone(PhoneNumber.of("555.123.4567", PhoneType.MOBILE))
                .notes("met at conference"));
        System.out.println("Created (likely duplicate): " + johnDuplicate);

        System.out.println("\n=== 2. Groups ===");
        Group family = system.groups().createGroup("Family");
        Group work = system.groups().createGroup("Work");
        contacts.addToGroup(john.id(), family.id());
        contacts.addToGroup(jane.id(), work.id());
        System.out.println("Family members: " + system.search().listGroupMembers(family.id()));

        System.out.println("\n=== 3. Prefix search (Trie) ===");
        System.out.println("Prefix 'john' -> " + namesOf(system.search().searchByNamePrefix("john", 10)));
        System.out.println("Prefix 'smi' -> " + namesOf(system.search().searchByNamePrefix("smi", 10)));

        System.out.println("\n=== 4. Favorites ===");
        contacts.setFavorite(jane.id(), true);
        System.out.println("Favorites: " + namesOf(system.search().listFavorites()));

        System.out.println("\n=== 5. Edit + undo (Memento) ===");
        Contact edited = contacts.update(john.id(), b -> b.notes("works at Acme Corp"));
        System.out.println("After edit: " + edited.notes());
        Contact undone = contacts.undo(john.id()).orElseThrow();
        System.out.println("After undo: '" + undone.notes() + "'");

        System.out.println("\n=== 6. Duplicate detection + merge ===");
        List<Contact> dupes = system.duplicates().findPotentialDuplicates(john);
        System.out.println("Potential duplicates of John: " + namesOf(dupes));
        ContactService.MergeResult mergeResult = contacts.mergeContacts(john.id(), johnDuplicate.id());
        System.out.println("Merged: " + mergeResult.merged());

        System.out.println("\n=== 7. vCard export/import ===");
        String exported = system.importExport().exportContact(mergeResult.merged());
        System.out.println(exported);
        Contact reimported = system.importExport().importContact(
                exported.replace("John Smith", "John Smith (Imported Copy)"));
        System.out.println("Re-imported: " + reimported);

        System.out.println("\n=== 8. Full address book ===");
        for (Contact c : contacts.getAll()) {
            System.out.println(" - " + c);
        }
    }

    private static List<String> namesOf(List<Contact> contactList) {
        return contactList.stream().map(Contact::displayName).toList();
    }
}
