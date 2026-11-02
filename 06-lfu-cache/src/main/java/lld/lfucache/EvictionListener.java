package lld.lfucache;

/**
 * Observer hook invoked synchronously whenever {@link LFUCache} evicts an
 * entry to make room for a new one.
 *
 * <p>This is deliberately a single-method functional interface rather than a
 * richer "cache event" hierarchy (no {@code onInsert}, {@code onUpdate},
 * {@code onHit} here): the only event an LLD interviewer typically probes is
 * "what happens to the evicted entry" (e.g. write-back to a database,
 * metrics, logging), and YAGNI argues against modelling events nobody asked
 * for. If richer lifecycle hooks are needed later, this interface can grow
 * additively without breaking existing implementations (default methods),
 * which preserves Open/Closed.
 *
 * <p><b>Threading:</b> the listener is invoked while {@link LFUCache} (or, in
 * the concurrent wrapper, {@link ConcurrentLFUCache}) may still hold its
 * internal lock, depending on configuration. Implementations must therefore
 * be fast and must not call back into the same cache instance, or a deadlock
 * is possible. See {@code STAFF_LEVEL_DEEP_DIVE.md} for the full discussion.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
@FunctionalInterface
public interface EvictionListener<K, V> {

    /**
     * Called exactly once per evicted entry, after it has been fully removed
     * from the cache's internal structures but before {@code put} returns.
     *
     * @param key   the evicted key
     * @param value the evicted value
     */
    void onEvict(K key, V value);
}
