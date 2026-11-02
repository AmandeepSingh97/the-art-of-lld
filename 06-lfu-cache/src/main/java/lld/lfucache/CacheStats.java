package lld.lfucache;

/**
 * An immutable snapshot of cache hit/miss/eviction counters.
 *
 * <p>Modelled as a {@code record} rather than a mutable stats object with
 * getters: a snapshot that could change out from under the caller after
 * being handed out would be actively misleading for monitoring code that
 * expects to log/export a consistent point-in-time view.
 *
 * @param hitCount      number of {@code get} calls that found a value
 * @param missCount     number of {@code get} calls that found nothing
 * @param evictionCount number of entries evicted to make room for new ones
 */
public record CacheStats(long hitCount, long missCount, long evictionCount) {

    public CacheStats {
        if (hitCount < 0 || missCount < 0 || evictionCount < 0) {
            throw new IllegalArgumentException("Counters must be non-negative");
        }
    }

    /** @return total number of {@code get} calls observed (hits + misses). */
    public long requestCount() {
        return hitCount + missCount;
    }

    /**
     * @return hit rate in {@code [0.0, 1.0]}, or {@code 0.0} if there have
     *         been no requests yet (defined as 0 rather than {@code NaN} so
     *         callers can use the value directly in dashboards/alerts
     *         without a special case for "no traffic yet").
     */
    public double hitRate() {
        long total = requestCount();
        return total == 0 ? 0.0 : (double) hitCount / total;
    }
}
