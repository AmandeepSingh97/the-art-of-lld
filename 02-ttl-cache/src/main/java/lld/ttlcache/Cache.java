package lld.ttlcache;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * A generic, thread-safe, in-memory key-value cache with per-entry time-to-live (TTL)
 * and a pluggable size-based eviction policy.
 *
 * <p>This is the single public entry point consumers should depend on; {@link TtlCache}
 * is the concrete implementation, constructed via {@link CacheBuilder}. Depending on the
 * interface rather than {@code TtlCache} directly keeps call sites decoupled from the
 * storage/eviction/reaper implementation details (Dependency Inversion), and allows a
 * no-op or in-memory test double to be substituted in unit tests of calling code.
 *
 * <p>All methods are safe to call concurrently from multiple threads without external
 * synchronization; see the "Concurrency and Thread Safety" section of this problem's
 * README for the detailed guarantees and the race conditions that are deliberately
 * avoided (notably: a {@code get()} racing an expiry sweep never returns a value that is
 * both "removed from the cache" and "handed to a caller").
 *
 * @param <K> key type. Must have correct, stable {@code equals}/{@code hashCode}
 *            implementations, since the cache is backed by a hash map.
 * @param <V> value type
 */
public interface Cache<K, V> extends AutoCloseable {

    /**
     * Looks up {@code key}.
     *
     * @param key the key to look up; must not be {@code null}
     * @return the associated value, or {@link Optional#empty()} if absent or expired
     * @throws NullPointerException if {@code key} is {@code null}
     */
    Optional<V> get(K key);

    /**
     * Associates {@code key} with {@code value} using this cache's configured default TTL.
     * Replaces any existing mapping for {@code key}.
     *
     * @throws NullPointerException if {@code key} or {@code value} is {@code null}
     */
    void put(K key, V value);

    /**
     * Associates {@code key} with {@code value}, expiring it after {@code ttl} elapses.
     * Replaces any existing mapping for {@code key}. Passing {@link Duration#ZERO} (or any
     * non-positive duration) means the entry never expires on its own (it can still be
     * evicted for size, or explicitly invalidated).
     *
     * @throws NullPointerException if any argument is {@code null}
     */
    void put(K key, V value, Duration ttl);

    /**
     * Atomic "get, or load and cache" operation: if {@code key} is present and not
     * expired, returns its value without invoking {@code loader}. Otherwise invokes
     * {@code loader} exactly once, stores the result with this cache's default TTL, and
     * returns it.
     *
     * <p>"Exactly once" holds even when many threads call this concurrently for the same
     * missing key (the classic cache-stampede / thundering-herd scenario): the cache
     * guarantees only one thread executes {@code loader} and the rest block briefly and
     * receive its result, rather than every thread independently recomputing or
     * re-fetching the value. See the Concurrency section of the README for how this is
     * implemented without a global lock.
     *
     * @throws NullPointerException if {@code key} or {@code loader} is {@code null}
     */
    V computeIfAbsent(K key, Function<? super K, ? extends V> loader);

    /**
     * Same as {@link #computeIfAbsent(Object, Function)} but with an explicit TTL for a
     * freshly loaded value.
     */
    V computeIfAbsent(K key, Function<? super K, ? extends V> loader, Duration ttl);

    /**
     * Removes any mapping for {@code key}, notifying listeners with
     * {@link EvictionCause#EXPLICIT} if a mapping existed. No-op if absent.
     */
    void invalidate(K key);

    /**
     * Removes all mappings. Each removed entry is reported to listeners individually with
     * {@link EvictionCause#EXPLICIT}.
     */
    void invalidateAll();

    /**
     * @return the current number of entries in the cache, including any that are
     *         logically expired but not yet lazily or actively reaped. Use {@link #get}
     *         semantics (not this count) to decide whether a given key is "live".
     */
    long size();

    /**
     * @return an immutable snapshot of hit/miss/eviction/expiration counters.
     */
    CacheStats stats();

    /**
     * Stops the background reaper thread. After calling this, lazy (on-access) expiry
     * still works correctly, but entries will no longer be actively swept out between
     * accesses, so {@link #size()} and memory usage can grow with "dead" expired entries
     * until something calls {@link #get} on each one. Idempotent: safe to call more than
     * once.
     */
    @Override
    void close();
}
