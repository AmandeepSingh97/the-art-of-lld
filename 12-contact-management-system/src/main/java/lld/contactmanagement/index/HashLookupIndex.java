package lld.contactmanagement.index;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic O(1)-average exact-match index from a normalized key (phone or
 * email) to the set of contact ids that currently hold it. Backed by
 * {@link ConcurrentHashMap} with {@link ConcurrentHashMap#newKeySet()} value
 * sets, so add/remove/lookup require no external locking: each is a single
 * lock-free (or per-bucket-locked, for structural map changes) operation
 * independent of the overall index size - unlike the name indexes, there is
 * no meaningful "algorithm choice" trade-off here because exact-match lookup
 * on a hash key has no better asymptotic alternative for this access
 * pattern. This is exactly the hash-index half of the "Trie vs sorted-index
 * vs hash-index" scoping requirement.
 */
public final class HashLookupIndex {

    private final ConcurrentHashMap<String, Set<UUID>> entries = new ConcurrentHashMap<>();

    public void add(String key, UUID contactId) {
        entries.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(contactId);
    }

    public void remove(String key, UUID contactId) {
        entries.computeIfPresent(key, (k, ids) -> {
            ids.remove(contactId);
            return ids.isEmpty() ? null : ids; // returning null removes the map entry
        });
    }

    public Set<UUID> lookup(String key) {
        Set<UUID> ids = entries.get(key);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }
}
