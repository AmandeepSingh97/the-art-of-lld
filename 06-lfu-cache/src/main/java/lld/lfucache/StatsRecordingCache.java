package lld.lfucache;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link Cache} decorator that records hit/miss/eviction counters without
 * changing the wrapped cache's eviction policy or thread-safety behavior.
 *
 * <p>This is the Decorator pattern applied for a concrete, narrow reason:
 * instrumentation is a cross-cutting concern independent of whether the
 * underlying policy is LFU, LRU, or anything else, and independent of
 * whether the delegate is already thread-safe. Baking counters into {@link
 * LFUCache} directly would (a) force every caller to pay for bookkeeping
 * they may not want, and (b) couple "what gets evicted" to "how we count
 * it," violating Single Responsibility. The alternative considered —
 * subclassing {@code LFUCache} to override {@code get}/{@code put} — was
 * rejected because {@code LFUCache} is {@code final} by design (its
 * invariants are only safe to mutate through its own methods), and because
 * inheritance here would couple the stats feature to one specific policy
 * implementation instead of any {@link Cache}.
 *
 * <p><b>Composition order matters.</b> To instrument a thread-safe cache,
 * wrap in this order: {@code new StatsRecordingCache<>(new
 * ConcurrentLFUCache<>(lfuCache))}. Counters here use {@link AtomicLong} so
 * this class is safe to compose either inside or outside a {@link
 * ConcurrentLFUCache}, but putting it outside (as above) means a {@code get}
 * that mutates LFU bookkeeping and the counter increment are two separate
 * atomic operations — acceptable because the counters are observational
 * metrics, not correctness-critical state, so their eventual-consistency
 * with the underlying cache's true state is a deliberate, documented
 * trade-off, not an oversight.
 *
 * <p><b>Eviction counting:</b> hits and misses are observable directly from
 * this decorator's own {@code get} calls, but evictions happen deep inside
 * {@link LFUCache} with no return value signalling them. Use {@link
 * #forLFUCache} to get a decorator wired up so evictions are counted
 * accurately; constructing this class directly around an already-built
 * delegate means evictions will not be observed and {@link
 * CacheStats#evictionCount()} will honestly report zero rather than guess.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class StatsRecordingCache<K, V> implements Cache<K, V> {

    private final Cache<K, V> delegate;
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong evictionCount;

    /**
     * Wraps an already-constructed {@code delegate}, counting hits/misses.
     * Eviction counting is not wired up by this constructor; prefer {@link
     * #forLFUCache} when the delegate is a fresh {@link LFUCache} you are
     * free to construct with an eviction listener.
     *
     * @param delegate the cache to instrument; must not be {@code null}
     */
    public StatsRecordingCache(Cache<K, V> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.evictionCount = new AtomicLong();
    }

    private StatsRecordingCache(Cache<K, V> delegate, AtomicLong evictionCount) {
        this.delegate = delegate;
        this.evictionCount = evictionCount;
    }

    /**
     * Convenience factory that wires up eviction counting correctly: builds
     * a fresh {@link LFUCache} whose eviction listener increments a counter
     * owned by the returned decorator.
     *
     * @param capacity the cache capacity
     * @param <K>      the key type
     * @param <V>      the value type
     * @return a {@link StatsRecordingCache} with accurate eviction counts
     */
    public static <K, V> StatsRecordingCache<K, V> forLFUCache(int capacity) {
        AtomicLong evictions = new AtomicLong();
        LFUCache<K, V> core = new LFUCache<>(capacity, (key, value) -> evictions.incrementAndGet());
        return new StatsRecordingCache<>(core, evictions);
    }

    /**
     * Same as {@link #forLFUCache(int)} but the delegate is additionally
     * wrapped in a {@link ConcurrentLFUCache}, giving a single object that
     * is both thread-safe and accurately instrumented. Package-private: the
     * public entry point is {@link CacheFactory#instrumentedThreadSafeLfuCache},
     * which keeps callers from having to know this composition order.
     *
     * @param capacity the cache capacity
     * @param <K>      the key type
     * @param <V>      the value type
     * @return a thread-safe, instrumented decorator
     */
    static <K, V> StatsRecordingCache<K, V> forThreadSafeLFUCache(int capacity) {
        AtomicLong evictions = new AtomicLong();
        LFUCache<K, V> core = new LFUCache<>(capacity, (key, value) -> evictions.incrementAndGet());
        Cache<K, V> threadSafe = new ConcurrentLFUCache<>(core);
        return new StatsRecordingCache<>(threadSafe, evictions);
    }

    @Override
    public Optional<V> get(K key) {
        Optional<V> result = delegate.get(key);
        if (result.isPresent()) {
            hitCount.incrementAndGet();
        } else {
            missCount.incrementAndGet();
        }
        return result;
    }

    @Override
    public void put(K key, V value) {
        delegate.put(key, value);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public int capacity() {
        return delegate.capacity();
    }

    /** @return an immutable snapshot of the counters observed so far. */
    public CacheStats stats() {
        return new CacheStats(hitCount.get(), missCount.get(), evictionCount.get());
    }
}
