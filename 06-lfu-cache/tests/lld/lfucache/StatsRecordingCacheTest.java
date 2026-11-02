package lld.lfucache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link StatsRecordingCache}: hit/miss counting, hit rate
 * computation, and — importantly — that evictions triggered deep inside the
 * wrapped {@link LFUCache} are actually observed, not just hits/misses on
 * the decorator's own methods.
 */
class StatsRecordingCacheTest {

    @Test
    void countsHitsAndMissesSeparately() {
        StatsRecordingCache<String, Integer> cache = StatsRecordingCache.forLFUCache(10);
        cache.put("a", 1);

        cache.get("a");       // hit
        cache.get("a");       // hit
        cache.get("missing"); // miss

        CacheStats stats = cache.stats();
        assertEquals(2, stats.hitCount());
        assertEquals(1, stats.missCount());
        assertEquals(3, stats.requestCount());
    }

    @Test
    void hitRateIsComputedCorrectly() {
        StatsRecordingCache<String, Integer> cache = StatsRecordingCache.forLFUCache(10);
        cache.put("a", 1);

        cache.get("a");       // hit
        cache.get("missing"); // miss

        assertEquals(0.5, cache.stats().hitRate(), 1e-9);
    }

    @Test
    void hitRateIsZeroWithNoRequestsRatherThanNaN() {
        StatsRecordingCache<String, Integer> cache = StatsRecordingCache.forLFUCache(10);

        assertEquals(0.0, cache.stats().hitRate());
    }

    @Test
    void evictionsTriggeredInsideTheWrappedLFUCacheAreCounted() {
        StatsRecordingCache<String, Integer> cache = StatsRecordingCache.forLFUCache(1);

        cache.put("a", 1);
        cache.put("b", 2); // evicts "a": capacity is 1

        assertEquals(1, cache.stats().evictionCount());
        assertTrue(cache.get("a").isEmpty());
        assertEquals(1, cache.size());
    }

    @Test
    void plainConstructorDoesNotObserveEvictionsFromAnUnwiredDelegate() {
        // Documents the honest-zero behavior described in the class
        // Javadoc: wrapping an already-built LFUCache (no shared listener)
        // means evictions are invisible to this decorator.
        LFUCache<String, Integer> plainDelegate = new LFUCache<>(1);
        StatsRecordingCache<String, Integer> cache = new StatsRecordingCache<>(plainDelegate);

        cache.put("a", 1);
        cache.put("b", 2); // evicts "a" inside plainDelegate, but no listener was wired

        assertEquals(0, cache.stats().evictionCount());
    }

    @Test
    void capacityAndSizeDelegateToTheWrappedCache() {
        StatsRecordingCache<String, Integer> cache = StatsRecordingCache.forLFUCache(5);
        cache.put("a", 1);
        cache.put("b", 2);

        assertEquals(5, cache.capacity());
        assertEquals(2, cache.size());
    }
}
