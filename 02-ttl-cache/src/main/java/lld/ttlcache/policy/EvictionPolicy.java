package lld.ttlcache.policy;

import java.util.Optional;

/**
 * Strategy interface (GoF Strategy pattern) for choosing which key to evict when the
 * cache is over its configured {@code maximumSize}. This is deliberately independent of
 * TTL/expiry: TTL-based removal is handled entirely by {@code TtlCache}/the reaper, so an
 * {@code EvictionPolicy} only ever needs to answer "if I had to drop one entry right now
 * to free space, which one?"
 *
 * <p>Implementations must be thread-safe: the cache calls {@link #onInsert}/{@link
 * #onAccess}/{@link #onRemove} concurrently from any caller thread, and
 * {@link #evictionCandidate()} concurrently from whichever thread is currently enforcing
 * capacity. See {@code LruEvictionPolicy} and {@code LfuEvictionPolicy} for the specific
 * locking strategy (a single internal lock scoped to the policy's own bookkeeping
 * structure only &mdash; never the cache's backing map).
 *
 * @param <K> key type. The policy only ever needs to track keys, never values.
 */
public interface EvictionPolicy<K> {

    /**
     * Called exactly once when {@code key} is newly inserted into the cache (not on a
     * replace of an existing key &mdash; that is reported via {@link #onAccess}).
     */
    void onInsert(K key);

    /**
     * Called when {@code key} is read via {@code get()} (a cache hit) or when an existing
     * key's value is replaced via {@code put()}. Both are "this key was just used"
     * signals from the policy's point of view.
     */
    void onAccess(K key);

    /**
     * Called when {@code key} leaves the cache for any reason (eviction, expiry, or
     * explicit invalidation), so the policy can drop its bookkeeping for that key. Must be
     * a no-op (not throw) if the key is not currently tracked.
     */
    void onRemove(K key);

    /**
     * @return the key the policy recommends evicting next to free capacity, or
     *         {@link Optional#empty()} if the policy has nothing left to recommend
     *         (e.g. its bookkeeping is empty). Does not remove the key from the policy's
     *         own bookkeeping; the caller must follow up with {@link #onRemove} once it
     *         has actually removed the entry from the cache.
     */
    Optional<K> evictionCandidate();

    /** Clears all bookkeeping, e.g. in response to {@code invalidateAll()}. */
    void clear();
}
