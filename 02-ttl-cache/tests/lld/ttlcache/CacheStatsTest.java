package lld.ttlcache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CacheStatsTest {

    @Test
    void hitRateAndMissRateAreZeroWhenThereHaveBeenNoRequests() {
        CacheStats stats = new CacheStats(0, 0, 0, 0, 0);

        assertEquals(0.0, stats.hitRate());
        assertEquals(0.0, stats.missRate());
    }

    @Test
    void hitRateAndMissRateSumToOneAndReflectCounts() {
        CacheStats stats = new CacheStats(3, 1, 0, 0, 2);

        assertEquals(0.75, stats.hitRate(), 1e-9);
        assertEquals(0.25, stats.missRate(), 1e-9);
        assertEquals(4, stats.requestCount());
    }

    @Test
    void allMissesGivesZeroHitRateAndFullMissRate() {
        CacheStats stats = new CacheStats(0, 5, 0, 0, 0);

        assertEquals(0.0, stats.hitRate());
        assertEquals(1.0, stats.missRate());
    }
}
