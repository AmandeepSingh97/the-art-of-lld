package lld.lfucache;

import java.util.Optional;

/**
 * A generic key/value cache abstraction.
 *
 * <p>This interface intentionally says nothing about the eviction policy, the
 * thread-safety guarantees, or whether hits/misses are tracked. Those are
 * orthogonal concerns layered on top via composition (see {@link
 * ConcurrentLFUCache} and {@link StatsRecordingCache}), which keeps this
 * contract small and lets callers depend on an abstraction rather than a
 * concrete implementation (Dependency Inversion).
 *
 * @param <K> the key type. Must have well-behaved {@code equals}/{@code
 *            hashCode} because keys are stored in a {@link java.util.HashMap}.
 * @param <V> the value type.
 */
public interface Cache<K, V> {

    /**
     * Looks up {@code key} in the cache.
     *
     * <p>A successful lookup is itself a mutation for policies such as LFU
     * and LRU: it changes the recency/frequency bookkeeping used to decide
     * future evictions. Implementations must document their own complexity,
     * but {@link LFUCache} guarantees amortized O(1).
     *
     * @param key the lookup key, must not be {@code null}
     * @return the cached value, or {@link Optional#empty()} if absent
     */
    Optional<V> get(K key);

    /**
     * Inserts or updates the value for {@code key}.
     *
     * <p>If the cache is at capacity and {@code key} is not already present,
     * an implementation-defined eviction policy removes exactly one entry to
     * make room. If {@code key} is already present, this is a value update
     * and (for frequency/recency policies) also counts as an access.
     *
     * @param key   the key, must not be {@code null}
     * @param value the value, must not be {@code null}
     */
    void put(K key, V value);

    /** @return the number of entries currently stored. */
    int size();

    /** @return the maximum number of entries this cache will hold. */
    int capacity();
}
