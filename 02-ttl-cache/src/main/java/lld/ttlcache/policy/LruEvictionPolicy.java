package lld.ttlcache.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Least-Recently-Used eviction policy.
 *
 * <p><b>Data structure:</b> a {@link LinkedHashMap} in access-order mode
 * ({@code accessOrder = true}). This gives O(1) amortized {@code get}/{@code put}/{@code
 * remove} while maintaining recency order automatically on every access, which is exactly
 * the classic "HashMap + doubly linked list" LRU structure &mdash; {@code LinkedHashMap}
 * already implements that internally, so reimplementing a bespoke doubly-linked list here
 * would duplicate the JDK's own data structure for no behavioural benefit. (A bespoke
 * implementation is called out as an alternative in the README's Trade-offs section, for
 * cases where avoiding {@code LinkedHashMap}'s rehash-on-resize pauses genuinely matters.)
 *
 * <p><b>Thread safety:</b> all operations are guarded by a single {@link ReentrantLock}
 * scoped to this policy instance only. This lock is never held while calling back into
 * the cache's backing map or into user listener code, so it cannot participate in a
 * lock-ordering deadlock with the cache's own operations. Contention is limited to
 * eviction-bookkeeping updates, which are O(1) and extremely short-held.
 *
 * @param <K> key type
 */
public final class LruEvictionPolicy<K> implements EvictionPolicy<K> {

    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<K, Boolean> recencyOrder = new LinkedHashMap<>(16, 0.75f, true);

    @Override
    public void onInsert(K key) {
        lock.lock();
        try {
            recencyOrder.put(key, Boolean.TRUE);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onAccess(K key) {
        lock.lock();
        try {
            // get() on an access-ordered LinkedHashMap moves the entry to the "most
            // recently used" end as a side effect; the returned value is irrelevant.
            if (recencyOrder.containsKey(key)) {
                recencyOrder.get(key);
            } else {
                // Defensive: onAccess called for a key the policy never saw onInsert for.
                // Treat it the same as a fresh insert rather than silently dropping it.
                recencyOrder.put(key, Boolean.TRUE);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onRemove(K key) {
        lock.lock();
        try {
            recencyOrder.remove(key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<K> evictionCandidate() {
        lock.lock();
        try {
            Map.Entry<K, Boolean> eldest = recencyOrder.entrySet().stream().findFirst().orElse(null);
            return eldest == null ? Optional.empty() : Optional.of(eldest.getKey());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            recencyOrder.clear();
        } finally {
            lock.unlock();
        }
    }
}
