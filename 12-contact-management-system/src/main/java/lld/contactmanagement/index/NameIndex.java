package lld.contactmanagement.index;

import java.util.Set;
import java.util.UUID;

/**
 * Strategy interface for prefix-searchable name indexes. Two implementations
 * are provided - {@link TrieNameIndex} and {@link SortedArrayNameIndex} -
 * trading memory and mutation cost against query latency differently. See
 * README section 10 (Data Structures and Algorithms) for the full complexity
 * comparison and when to pick each.
 *
 * <p>Both implementations index individual name <em>tokens</em> (e.g. first
 * name, last name separately), not the whole display name, so a prefix
 * search on "smi" finds "John Smith" even though the display name begins
 * with "John".
 */
public interface NameIndex {

    /** Indexes one token (already normalized by the caller) for a contact id. */
    void add(String token, UUID contactId);

    /** Removes one token -> contact id association. No-op if absent. */
    void remove(String token, UUID contactId);

    /**
     * Returns the set of contact ids whose indexed tokens start with
     * {@code prefix} (already normalized by the caller). Order is
     * unspecified; callers sort/paginate the resolved {@code Contact}
     * objects as needed.
     */
    Set<UUID> searchByPrefix(String prefix);
}
