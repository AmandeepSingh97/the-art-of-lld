package lld.ttlcache.policy;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Least-Frequently-Used eviction policy with O(1) {@code onAccess}/{@code
 * evictionCandidate}, using the classic "frequency buckets" structure (the same idea as
 * LeetCode's O(1) LFU cache):
 *
 * <ul>
 *   <li>{@code frequencyOf}: key -&gt; current access count.</li>
 *   <li>{@code keysByFrequency}: frequency -&gt; insertion-ordered set of keys currently
 *       at that frequency. Using a {@link TreeMap} keyed by frequency means the
 *       minimum-frequency bucket is always {@code keysByFrequency.firstEntry()} in O(log F)
 *       where F is the number of distinct frequencies currently in use (not the number of
 *       keys) &mdash; in practice F is small and bounded by how long entries live, so this
 *       is effectively O(1) for realistic workloads. A plain {@code HashMap} plus a
 *       separately tracked {@code minFrequency} counter would be strictly O(1), but adds
 *       bookkeeping complexity (recomputing {@code minFrequency} after it's evicted) for a
 *       negligible win at interview/production scale; this is called out as the pure-O(1)
 *       alternative in the README.</li>
 * </ul>
 *
 * <p>Ties within the minimum-frequency bucket are broken by insertion order (the
 * {@link LinkedHashSet} iteration order), i.e. "least frequently used, and among those,
 * least recently touched" &mdash; the standard LFU tie-break.
 *
 * <p><b>Thread safety:</b> identical rationale to {@link LruEvictionPolicy}: a single
 * {@link ReentrantLock} scoped to this policy's own bookkeeping, held only for O(1)/O(log
 * F) structural updates, never across a call into the cache or a listener.
 *
 * @param <K> key type
 */
public final class LfuEvictionPolicy<K> implements EvictionPolicy<K> {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<K, Integer> frequencyOf = new HashMap<>();
    private final TreeMap<Integer, LinkedHashSet<K>> keysByFrequency = new TreeMap<>();

    @Override
    public void onInsert(K key) {
        lock.lock();
        try {
            insertAtFrequency(key, 1);
            frequencyOf.put(key, 1);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onAccess(K key) {
        lock.lock();
        try {
            Integer currentFrequency = frequencyOf.get(key);
            if (currentFrequency == null) {
                // Defensive, mirrors LruEvictionPolicy: treat as a fresh insert.
                insertAtFrequency(key, 1);
                frequencyOf.put(key, 1);
                return;
            }
            removeFromBucket(currentFrequency, key);
            int nextFrequency = currentFrequency + 1;
            insertAtFrequency(key, nextFrequency);
            frequencyOf.put(key, nextFrequency);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onRemove(K key) {
        lock.lock();
        try {
            Integer currentFrequency = frequencyOf.remove(key);
            if (currentFrequency != null) {
                removeFromBucket(currentFrequency, key);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<K> evictionCandidate() {
        lock.lock();
        try {
            Map.Entry<Integer, LinkedHashSet<K>> minBucket = keysByFrequency.firstEntry();
            if (minBucket == null || minBucket.getValue().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(minBucket.getValue().iterator().next());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            frequencyOf.clear();
            keysByFrequency.clear();
        } finally {
            lock.unlock();
        }
    }

    /** Caller must hold {@link #lock}. */
    private void insertAtFrequency(K key, int frequency) {
        keysByFrequency.computeIfAbsent(frequency, f -> new LinkedHashSet<>()).add(key);
    }

    /** Caller must hold {@link #lock}. */
    private void removeFromBucket(int frequency, K key) {
        LinkedHashSet<K> bucket = keysByFrequency.get(frequency);
        if (bucket == null) {
            return;
        }
        bucket.remove(key);
        if (bucket.isEmpty()) {
            keysByFrequency.remove(frequency);
        }
    }
}
