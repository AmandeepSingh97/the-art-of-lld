package lld.lfucache;

/**
 * Static factory methods for assembling the common {@link Cache}
 * configurations out of the individual decorators, so callers do not need to
 * know the correct composition order (see {@link StatsRecordingCache} and
 * {@link ConcurrentLFUCache} Javadoc for why order matters).
 *
 * <p>This is a thin, deliberately non-extensible factory (a handful of
 * static methods, not an abstract-factory hierarchy with pluggable
 * providers): there is exactly one family of products (LFU-policy caches)
 * and a small, enumerable set of cross-cutting combinations (plain,
 * thread-safe, instrumented, both). Introducing a {@code CacheBuilder} or a
 * registry of named policies would be solving a problem this scope does not
 * have yet — if a second eviction policy (e.g. LRU) is added later, this
 * class can grow a parallel {@code lruCache(...)} method without any
 * existing caller breaking.
 */
public final class CacheFactory {

    private CacheFactory() {
        // Static-methods-only utility class; instantiation is never
        // meaningful, so the constructor is hidden rather than left public
        // and undocumented.
    }

    /**
     * @param capacity the cache capacity
     * @param <K>      the key type
     * @param <V>      the value type
     * @return a plain, single-threaded {@link LFUCache}
     */
    public static <K, V> Cache<K, V> lfuCache(int capacity) {
        return new LFUCache<>(capacity);
    }

    /**
     * @param capacity the cache capacity
     * @param <K>      the key type
     * @param <V>      the value type
     * @return an {@link LFUCache} safe for concurrent use from multiple
     *         threads, guarded by a single coarse-grained lock
     */
    public static <K, V> Cache<K, V> threadSafeLfuCache(int capacity) {
        return new ConcurrentLFUCache<>(new LFUCache<>(capacity));
    }

    /**
     * @param capacity the cache capacity
     * @param <K>      the key type
     * @param <V>      the value type
     * @return a single-threaded {@link LFUCache} with accurate hit/miss/
     *         eviction counters, reachable via {@link
     *         StatsRecordingCache#stats()}; callers who need metrics must
     *         hold onto the concrete return type rather than the {@link
     *         Cache} interface.
     */
    public static <K, V> StatsRecordingCache<K, V> instrumentedLfuCache(int capacity) {
        return StatsRecordingCache.forLFUCache(capacity);
    }

    /**
     * @param capacity the cache capacity
     * @param <K>      the key type
     * @param <V>      the value type
     * @return a thread-safe, instrumented {@link LFUCache}: hits/misses/
     *         evictions are counted and every operation is safe to call
     *         concurrently.
     */
    public static <K, V> StatsRecordingCache<K, V> instrumentedThreadSafeLfuCache(int capacity) {
        return StatsRecordingCache.forThreadSafeLFUCache(capacity);
    }
}
