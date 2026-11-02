package lld.ttlcache;

/**
 * Observer callback invoked whenever an entry leaves the cache, for any reason captured
 * by {@link EvictionCause}.
 *
 * <p><b>Threading contract:</b> listeners may be invoked concurrently, from caller threads
 * (on {@code put}/{@code get}/{@code invalidate}) and from the background reaper thread.
 * Implementations must be thread-safe and should return quickly &mdash; by default a
 * listener runs synchronously on whichever thread triggered the removal, so a slow
 * listener directly adds latency to that cache operation (or delays the next reaper
 * sweep). If a listener needs to do non-trivial work (I/O, logging to a remote sink),
 * register it via {@link CacheBuilder#listenerExecutor(java.util.concurrent.Executor)}
 * so dispatch is decoupled from the calling thread.
 *
 * <p>A listener that throws is caught and logged by the cache; it never propagates out of
 * a cache operation and never prevents the removal it is being notified about (the removal
 * has already happened by the time the listener runs).
 *
 * @param <K> key type
 * @param <V> value type
 */
@FunctionalInterface
public interface CacheEvictionListener<K, V> {

    /**
     * @param key   the key that was removed
     * @param value the value that was associated with {@code key} immediately before removal
     * @param cause why the entry was removed
     */
    void onEviction(K key, V value, EvictionCause cause);
}
