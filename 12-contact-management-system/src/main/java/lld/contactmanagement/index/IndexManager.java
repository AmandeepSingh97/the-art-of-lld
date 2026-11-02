package lld.contactmanagement.index;

import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.ContactChangeEvent;
import lld.contactmanagement.service.ContactChangeListener;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Subscribes to {@code ContactRepository} change events (Observer pattern)
 * and keeps every secondary index synchronized with storage: a
 * {@link NameIndex} (prefix search), two {@link HashLookupIndex}es (exact
 * phone/email lookup), a favorites id set, and a reverse group-membership
 * index ({@code groupId -> contact ids}, used to cascade group deletion in
 * O(members) instead of an O(n) full contact scan).
 *
 * <p>The repository and {@code ContactService} have zero awareness that
 * indexes exist - they only publish events. This keeps indexing a pure
 * read-side concern that can be extended (e.g. a new index type) without
 * touching write-path code, and makes {@code IndexManager} independently
 * unit-testable by feeding it synthetic events.
 *
 * <p><b>Reindexing strategy on UPDATE.</b> Rather than diffing {@code
 * before} vs {@code after} field-by-field, an update simply de-indexes
 * everything from {@code before} and re-indexes everything from {@code
 * after}. For a single contact this is O(F) work where F is the small,
 * bounded number of phones/emails/name-tokens/groups on one contact (not
 * O(n) over all contacts) - cheap and far simpler/less bug-prone than a
 * precise diff, at the cost of a constant-factor amount of redundant
 * remove+add churn when, say, only the favorite flag changed.
 */
public final class IndexManager implements ContactChangeListener {

    private final NameIndex nameIndex;
    private final HashLookupIndex phoneIndex = new HashLookupIndex();
    private final HashLookupIndex emailIndex = new HashLookupIndex();
    private final Set<UUID> favoriteIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Set<UUID>> groupMembers = new ConcurrentHashMap<>();

    public IndexManager(NameIndex nameIndex) {
        this.nameIndex = nameIndex;
    }

    @Override
    public void onChange(ContactChangeEvent event) {
        switch (event.type()) {
            case CREATED -> indexAll(event.after());
            case DELETED -> deindexAll(event.before());
            case UPDATED -> {
                deindexAll(event.before());
                indexAll(event.after());
            }
        }
    }

    private void indexAll(Contact contact) {
        for (String token : contact.nameTokens()) {
            nameIndex.add(token, contact.id());
        }
        contact.phoneNumbers().forEach(p -> phoneIndex.add(p.normalized(), contact.id()));
        contact.emailAddresses().forEach(e -> emailIndex.add(e.value(), contact.id()));
        if (contact.favorite()) {
            favoriteIds.add(contact.id());
        }
        for (UUID groupId : contact.groupIds()) {
            groupMembers.computeIfAbsent(groupId, k -> ConcurrentHashMap.newKeySet()).add(contact.id());
        }
    }

    private void deindexAll(Contact contact) {
        for (String token : contact.nameTokens()) {
            nameIndex.remove(token, contact.id());
        }
        contact.phoneNumbers().forEach(p -> phoneIndex.remove(p.normalized(), contact.id()));
        contact.emailAddresses().forEach(e -> emailIndex.remove(e.value(), contact.id()));
        favoriteIds.remove(contact.id());
        for (UUID groupId : contact.groupIds()) {
            groupMembers.computeIfPresent(groupId, (k, ids) -> {
                ids.remove(contact.id());
                return ids.isEmpty() ? null : ids;
            });
        }
    }

    public Set<UUID> searchByNamePrefix(String normalizedPrefix) {
        return nameIndex.searchByPrefix(normalizedPrefix);
    }

    public Set<UUID> findByNormalizedPhone(String normalizedPhone) {
        return phoneIndex.lookup(normalizedPhone);
    }

    public Set<UUID> findByNormalizedEmail(String normalizedEmail) {
        return emailIndex.lookup(normalizedEmail);
    }

    public Set<UUID> favoriteIds() {
        return Set.copyOf(favoriteIds);
    }

    public Set<UUID> groupMemberIds(UUID groupId) {
        Set<UUID> ids = groupMembers.get(groupId);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }
}
