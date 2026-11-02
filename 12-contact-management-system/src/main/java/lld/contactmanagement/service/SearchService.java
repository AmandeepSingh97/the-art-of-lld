package lld.contactmanagement.service;

import lld.contactmanagement.index.IndexManager;
import lld.contactmanagement.model.Contact;
import lld.contactmanagement.util.ContactNormalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-side facade over {@link IndexManager} and {@link ContactRepository}:
 * resolves index hits (contact ids) into full, current {@link Contact}
 * objects. Kept separate from {@link ContactService} because search is a
 * pure query concern with no business invariants to enforce - merging it
 * into {@code ContactService} would mix write-path validation/locking logic
 * with read-path index plumbing for no benefit (the Staff-Level Deep Dive
 * document discusses this SRP boundary explicitly).
 */
public final class SearchService {

    private final IndexManager indexManager;
    private final ContactRepository repository;

    public SearchService(IndexManager indexManager, ContactRepository repository) {
        this.indexManager = indexManager;
        this.repository = repository;
    }

    /**
     * Prefix search by name token (first or last name). O(|prefix|) index
     * lookup (see {@code NameIndex} implementations) plus O(k log k) to sort
     * the k matches alphabetically and apply the limit.
     */
    public List<Contact> searchByNamePrefix(String prefix, int limit) {
        String normalized = ContactNormalizer.normalizeName(prefix);
        Set<UUID> ids = indexManager.searchByNamePrefix(normalized);
        return resolveSorted(ids, limit);
    }

    public List<Contact> searchByPhone(String rawPhone) {
        String normalized = ContactNormalizer.normalizePhone(rawPhone);
        return resolveSorted(indexManager.findByNormalizedPhone(normalized), Integer.MAX_VALUE);
    }

    public List<Contact> searchByEmail(String rawEmail) {
        String normalized = ContactNormalizer.normalizeEmail(rawEmail);
        return resolveSorted(indexManager.findByNormalizedEmail(normalized), Integer.MAX_VALUE);
    }

    public List<Contact> listFavorites() {
        return resolveSorted(indexManager.favoriteIds(), Integer.MAX_VALUE);
    }

    public List<Contact> listGroupMembers(UUID groupId) {
        return resolveSorted(indexManager.groupMemberIds(groupId), Integer.MAX_VALUE);
    }

    private List<Contact> resolveSorted(Set<UUID> ids, int limit) {
        List<Contact> contacts = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            repository.findById(id).ifPresent(contacts::add);
        }
        contacts.sort(Comparator.comparing(Contact::displayName));
        if (contacts.size() > limit) {
            return List.copyOf(contacts.subList(0, limit));
        }
        return List.copyOf(contacts);
    }
}
