package lld.ttlcache;

import java.util.concurrent.atomic.LongAdder;

/**
 * Mutable, thread-safe, lock-free counters backing {@link CacheStats} snapshots.
 *
 * <p><b>Why {@link LongAdder} instead of {@code AtomicLong}:</b> under high read
 * concurrency (many threads incrementing hit/miss counts on every {@code get()}), a
 * single {@code AtomicLong} becomes a contention hotspot &mdash; every thread CASes the
 * same cache line. {@code LongAdder} stripes the counter across internal cells so
 * concurrent increments mostly hit different cells, trading a (small, bounded) memory
 * overhead and slightly more expensive {@code sum()} reads for far better increment
 * throughput. Since increments happen on every single cache operation but {@code sum()}
 * only happens when a caller explicitly asks for {@link CacheStats}, this trade is
 * exactly right for a cache's access pattern.
 *
 * <p>This class is intentionally package-private: it is an implementation detail of
 * {@link TtlCache}, not part of the public API surface. Callers only ever see immutable
 * {@link CacheStats} snapshots.
 */
final class CacheStatsRecorder {

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder expirations = new LongAdder();

    void recordHit() {
        hits.increment();
    }

    void recordMiss() {
        misses.increment();
    }

    void recordEviction() {
        evictions.increment();
    }

    void recordExpiration() {
        expirations.increment();
    }

    /**
     * Builds an immutable point-in-time snapshot. Because the four counters are updated
     * independently (not under a shared lock), a snapshot taken concurrently with other
     * operations may reflect a state that never existed at any single instant (e.g. a hit
     * counted but the corresponding size change not yet visible). This is an accepted,
     * deliberate trade-off: stats are an observability aid, not a source of truth for
     * correctness, so eventual/approximate consistency is sufficient and far cheaper than
     * a globally consistent snapshot would be.
     */
    CacheStats snapshot(long currentSize) {
        return new CacheStats(hits.sum(), misses.sum(), evictions.sum(), expirations.sum(), currentSize);
    }
}
