package lld.contactmanagement.index;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Sorted-array + binary-search implementation of {@link NameIndex}.
 *
 * <p>Maintains a single flat, sorted {@code List<Entry>} of
 * {@code (token, contactId)} pairs, ordered by token then by contactId for a
 * deterministic tie-break. A prefix search binary-searches for the first
 * entry {@code >= prefix}, then linearly scans forward while the entry's
 * token still starts with {@code prefix}.
 *
 * <p><b>Why this exists alongside {@link TrieNameIndex}.</b> It is the
 * classic alternative interviewers expect you to at least mention, and it
 * has a genuinely different trade-off profile - see README section 10 for
 * the full comparison table. In short: much lower memory per entry (no
 * per-character node/map overhead), simpler to implement/debug, O(log n + k)
 * query; but O(n) insert/delete (array shift) versus the trie's O(L)
 * (L = token length, independent of n).
 *
 * <p><b>Concurrency.</b> Same coarse {@link ReentrantReadWriteLock} strategy
 * as {@link TrieNameIndex} for an apples-to-apples comparison: the chosen
 * algorithm is orthogonal to the synchronization strategy.
 */
public final class SortedArrayNameIndex implements NameIndex {

    private record Entry(String token, UUID contactId) implements Comparable<Entry> {
        @Override
        public int compareTo(Entry other) {
            int byToken = token.compareTo(other.token);
            if (byToken != 0) {
                return byToken;
            }
            return contactId.compareTo(other.contactId);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public void add(String token, UUID contactId) {
        if (token == null || token.isEmpty()) {
            return;
        }
        Entry entry = new Entry(token, contactId);
        lock.writeLock().lock();
        try {
            int insertionPoint = lowerBound(entry);
            // Avoid duplicate (token, contactId) pairs on repeated adds.
            if (insertionPoint < entries.size() && entries.get(insertionPoint).equals(entry)) {
                return;
            }
            entries.add(insertionPoint, entry); // O(n) due to array shift
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(String token, UUID contactId) {
        if (token == null || token.isEmpty()) {
            return;
        }
        Entry entry = new Entry(token, contactId);
        lock.writeLock().lock();
        try {
            int index = lowerBound(entry);
            if (index < entries.size() && entries.get(index).equals(entry)) {
                entries.remove(index); // O(n) due to array shift
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Set<UUID> searchByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return Set.of();
        }
        lock.readLock().lock();
        try {
            int start = lowerBoundByToken(prefix);
            Set<UUID> result = new LinkedHashSet<>();
            for (int i = start; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (!e.token().startsWith(prefix)) {
                    break; // sorted order guarantees no further matches
                }
                result.add(e.contactId());
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Index of the first entry {@code >= target} (standard binary-search lower bound). */
    private int lowerBound(Entry target) {
        int lo = 0;
        int hi = entries.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (entries.get(mid).compareTo(target) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Index of the first entry whose token is {@code >= prefix}. */
    private int lowerBoundByToken(String prefix) {
        int lo = 0;
        int hi = entries.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (entries.get(mid).token().compareTo(prefix) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
