package lld.ttlcache;

/**
 * Immutable snapshot of cache statistics at a point in time.
 *
 * <p>This is a plain value object returned by {@link Cache#stats()} &mdash; it never
 * changes after construction, so callers can safely read it without synchronization and
 * without it drifting mid-read. The live counters backing it ({@code CacheStatsRecorder})
 * are mutable and lock-free; see that class for the concurrency rationale.
 *
 * @param hitCount        number of {@code get()} calls that returned a present, non-expired value
 * @param missCount       number of {@code get()} calls that found no value (absent or expired)
 * @param evictionCount   number of entries removed because the cache was over {@code maximumSize}
 * @param expirationCount number of entries removed because their TTL elapsed (lazy or reaper)
 * @param size            number of entries in the cache at snapshot time
 */
public record CacheStats(
        long hitCount,
        long missCount,
        long evictionCount,
        long expirationCount,
        long size) {

    /**
     * @return hits / (hits + misses), or {@code 0.0} if there have been no requests yet.
     */
    public double hitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }

    /**
     * @return misses / (hits + misses), or {@code 0.0} if there have been no requests yet.
     */
    public double missRate() {
        long total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) missCount / total;
    }

    /**
     * @return total number of {@code get()} requests this snapshot reflects.
     */
    public long requestCount() {
        return hitCount + missCount;
    }
}
