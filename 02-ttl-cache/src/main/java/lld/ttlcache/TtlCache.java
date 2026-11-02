package lld.ttlcache;

import lld.ttlcache.policy.EvictionPolicy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Thread-safe, generic TTL cache. Construct via {@link CacheBuilder#newBuilder()}; do not
 * call this constructor directly from outside the package.
 *
 * <h2>Concurrency design summary</h2>
 *
 * <p>The backing store is a {@link ConcurrentHashMap}, chosen specifically because it
 * gives lock-free reads and fine-grained (bin-level), non-blocking-for-unrelated-keys
 * writes out of the box &mdash; there is deliberately no cache-wide lock guarding
 * {@code get}/{@code put}, which is the single most important property of this design:
 * two threads operating on different keys never block each other, and a thread reading
 * key A is never blocked by a thread writing key B.
 *
 * <p>Three specific race conditions are called out because they are easy to get wrong:
 *
 * <ol>
 *   <li><b>Returning an expired value.</b> {@code get()} re-checks expiry against the
 *       entry it actually read (not a separate lookup), so there is no "check-then-act"
 *       gap between deciding an entry is live and returning its value. Once {@code get()}
 *       has read a non-expired {@code CacheEntry} reference, that reference is immutable
 *       (see {@link CacheEntry}), so nothing can mutate the value out from under it after
 *       the expiry check passes.</li>
 *   <li><b>Double-removing / removing the wrong generation of an entry.</b> Both the lazy
 *       expiry path (in {@code get}) and the background reaper remove an expired entry
 *       with the conditional {@code map.remove(key, expectedEntry)} rather than
 *       {@code map.remove(key)}. This matters because a key can be replaced with a fresh,
 *       non-expired value (via {@code put}) in the gap between "I observed this entry was
 *       expired" and "I go to remove it". An unconditional remove would then delete the
 *       *new* value; the conditional remove only succeeds if the entry is still the exact
 *       (by reference) expired one it already identified, otherwise it's a safe no-op and
 *       the newer entry survives untouched.</li>
 *   <li><b>Cache-stampede on {@code computeIfAbsent}.</b> Loading is performed inside
 *       {@link ConcurrentHashMap#compute}, which CHM guarantees is atomic per key (other
 *       threads calling {@code compute}/{@code put}/{@code get} for the *same* key block
 *       briefly until the remapping function returns; threads touching *other* keys are
 *       unaffected). So when N threads race to load the same missing key, exactly one
 *       invokes the loader and the rest observe its result, instead of N redundant loads
 *       or N overlapping writes.</li>
 * </ol>
 *
 * <p><b>Size-based eviction</b> is coordinated by a single {@link ReentrantLock}
 * ({@code evictionLock}) that is scoped only to the "enforce capacity" sequence, not to
 * {@code get}/{@code put} themselves. It uses {@code tryLock()} rather than {@code lock()}:
 * if eviction is already in progress on another thread, the current thread simply skips
 * triggering a redundant eviction pass (the in-progress pass will bring the cache back
 * under capacity); it does not block waiting its turn. This avoids turning capacity
 * enforcement into a point of contention under heavy concurrent writes, at the cost of a
 * cache that can transiently sit slightly over {@code maximumSize} under a burst of
 * concurrent inserts &mdash; acceptable because {@code maximumSize} is a soft target for
 * memory planning, not a hard correctness invariant.
 *
 * <p><b>Eviction-policy bookkeeping</b> (the LRU/LFU ordering structures) is guarded by a
 * lock owned by the {@link EvictionPolicy} implementation itself, entirely separate from
 * both the map and {@code evictionLock}. The cache always updates the map first and the
 * policy second, and never holds the policy's lock while touching the map or vice versa
 * &mdash; a fixed lock order (none needed, since the two never nest) that makes deadlock
 * between them structurally impossible. The visible consequence is a narrow, benign
 * window where an entry is in the map but not yet reflected in recency/frequency order;
 * since the policy is only ever consulted for "which entry to evict", a slightly stale
 * ordering during that window never causes an incorrect {@code get}/{@code put} result,
 * only (at most) a marginally different eviction choice.
 */
public final class TtlCache<K, V> implements Cache<K, V> {

    private final ConcurrentHashMap<K, CacheEntry<V>> store = new ConcurrentHashMap<>();
    private final ReentrantLock evictionLock = new ReentrantLock();

    private final long maximumSize;
    private final Duration defaultTtl;
    private final EvictionPolicy<K> evictionPolicy;
    private final Clock clock;
    private final List<CacheEvictionListener<K, V>> listeners;
    private final Executor listenerExecutor;
    private final CacheStatsRecorder stats = new CacheStatsRecorder();

    private final ScheduledExecutorService reaper;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    TtlCache(
            long maximumSize,
            Duration defaultTtl,
            EvictionPolicy<K> evictionPolicy,
            Duration reaperInterval,
            Clock clock,
            List<CacheEvictionListener<K, V>> listeners,
            Executor listenerExecutor) {
        this.maximumSize = maximumSize;
        this.defaultTtl = defaultTtl;
        this.evictionPolicy = evictionPolicy;
        this.clock = clock;
        this.listeners = listeners;
        this.listenerExecutor = listenerExecutor;

        this.reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ttl-cache-reaper");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = reaperInterval.toMillis();
        this.reaper.scheduleAtFixedRate(
                this::reapExpiredEntries, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        CacheEntry<V> entry = store.get(key);
        if (entry == null) {
            stats.recordMiss();
            return Optional.empty();
        }
        if (entry.isExpiredAt(clock.currentTimeMillis())) {
            expireIfStillPresent(key, entry);
            stats.recordMiss();
            return Optional.empty();
        }
        evictionPolicy.onAccess(key);
        stats.recordHit();
        return Optional.of(entry.value());
    }

    @Override
    public void put(K key, V value) {
        put(key, value, defaultTtl);
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(ttl, "ttl");

        long expireAtMillis = expiryFor(ttl, clock.currentTimeMillis());
        CacheEntry<V> newEntry = new CacheEntry<>(value, expireAtMillis);
        CacheEntry<V> previous = store.put(key, newEntry);

        if (previous == null) {
            evictionPolicy.onInsert(key);
            enforceCapacity();
        } else {
            evictionPolicy.onAccess(key);
            notifyListeners(key, previous.value(), EvictionCause.REPLACED);
        }
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> loader) {
        return computeIfAbsent(key, loader, defaultTtl);
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> loader, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(ttl, "ttl");

        long now = clock.currentTimeMillis();
        AtomicBoolean loadedFreshValue = new AtomicBoolean(false);

        // ConcurrentHashMap#compute is atomic per-key: concurrent callers racing on the
        // same key serialize through this remapping function, so the loader runs exactly
        // once even under a stampede of simultaneous first-access callers. Callers on
        // unrelated keys are not blocked by this.
        CacheEntry<V> result = store.compute(key, (k, existing) -> {
            if (existing != null && !existing.isExpiredAt(now)) {
                return existing;
            }
            V loadedValue = loader.apply(k);
            Objects.requireNonNull(loadedValue, "loader must not return null");
            loadedFreshValue.set(true);
            return new CacheEntry<>(loadedValue, expiryFor(ttl, now));
        });

        if (loadedFreshValue.get()) {
            evictionPolicy.onInsert(key);
            stats.recordMiss();
            enforceCapacity();
        } else {
            evictionPolicy.onAccess(key);
            stats.recordHit();
        }
        return result.value();
    }

    @Override
    public void invalidate(K key) {
        Objects.requireNonNull(key, "key");
        CacheEntry<V> removed = store.remove(key);
        if (removed != null) {
            evictionPolicy.onRemove(key);
            notifyListeners(key, removed.value(), EvictionCause.EXPLICIT);
        }
    }

    @Override
    public void invalidateAll() {
        for (K key : store.keySet()) {
            invalidate(key);
        }
        evictionPolicy.clear();
    }

    @Override
    public long size() {
        return store.mappingCount();
    }

    @Override
    public CacheStats stats() {
        return stats.snapshot(store.mappingCount());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            reaper.shutdownNow();
        }
    }

    private long expiryFor(Duration ttl, long nowMillis) {
        if (ttl.isZero() || ttl.isNegative()) {
            return CacheEntry.NO_EXPIRY;
        }
        return nowMillis + ttl.toMillis();
    }

    /**
     * Removes {@code key} only if it still maps to exactly {@code expectedEntry} (the
     * same object reference already identified as expired by the caller). See the class
     * javadoc, race (2), for why this must be a conditional remove and not
     * {@code store.remove(key)}.
     */
    private void expireIfStillPresent(K key, CacheEntry<V> expectedEntry) {
        if (store.remove(key, expectedEntry)) {
            evictionPolicy.onRemove(key);
            stats.recordExpiration();
            notifyListeners(key, expectedEntry.value(), EvictionCause.EXPIRED);
        }
    }

    /**
     * Background reaper sweep: iterates the map's weakly-consistent key set (safe to
     * iterate while other threads concurrently insert/remove &mdash; it reflects *some*
     * consistent state as of some point during the iteration, per
     * {@link ConcurrentHashMap}'s documented guarantees) and conditionally removes any
     * entry that is expired as of this sweep's timestamp.
     */
    private void reapExpiredEntries() {
        long now = clock.currentTimeMillis();
        for (Map.Entry<K, CacheEntry<V>> entry : store.entrySet()) {
            CacheEntry<V> value = entry.getValue();
            if (value.isExpiredAt(now)) {
                expireIfStillPresent(entry.getKey(), value);
            }
        }
    }

    /**
     * Enforces {@code maximumSize} by repeatedly asking the eviction policy for a victim
     * and removing it, until the cache is at or under capacity. Guarded by
     * {@code tryLock()} rather than {@code lock()} &mdash; see the class javadoc for why a
     * thread that cannot immediately acquire the lock simply skips this pass instead of
     * blocking.
     */
    private void enforceCapacity() {
        if (store.mappingCount() <= maximumSize) {
            return;
        }
        if (!evictionLock.tryLock()) {
            return;
        }
        try {
            while (store.mappingCount() > maximumSize) {
                Optional<K> candidate = evictionPolicy.evictionCandidate();
                if (candidate.isEmpty()) {
                    break;
                }
                K keyToEvict = candidate.get();
                CacheEntry<V> removed = store.remove(keyToEvict);
                evictionPolicy.onRemove(keyToEvict);
                if (removed != null) {
                    stats.recordEviction();
                    notifyListeners(keyToEvict, removed.value(), EvictionCause.SIZE);
                }
            }
        } finally {
            evictionLock.unlock();
        }
    }

    private void notifyListeners(K key, V value, EvictionCause cause) {
        for (CacheEvictionListener<K, V> listener : listeners) {
            listenerExecutor.execute(() -> {
                try {
                    listener.onEviction(key, value, cause);
                } catch (RuntimeException e) {
                    // A misbehaving listener must never break cache operations or the
                    // reaper thread; the removal it is being told about has already
                    // happened regardless of whether the listener succeeds.
                    System.err.println(
                            "TtlCache: eviction listener threw for key=" + key + ", cause=" + cause + ": " + e);
                }
            });
        }
    }
}
