package lld.ttlcache;

import lld.ttlcache.policy.LfuEvictionPolicy;
import lld.ttlcache.policy.LruEvictionPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Size-based eviction behaviour, exercised through the public {@link TtlCache} API (as
 * opposed to {@code LruEvictionPolicyTest}/{@code LfuEvictionPolicyTest}, which test the
 * strategies in isolation).
 */
final class TtlCacheEvictionTest {

    @Test
    void lruEvictsLeastRecentlyUsedKeyWhenOverCapacity() {
        List<Object> evicted = new ArrayList<>();
        try (TtlCache<Integer, String> cache = CacheBuilder.<Integer, String>newBuilder()
                .maximumSize(2)
                .evictionPolicy(LruEvictionPolicy::new)
                .addListener((key, value, cause) -> evicted.add(key))
                .build()) {

            cache.put(1, "one");
            cache.put(2, "two");
            cache.get(1); // 1 is now most-recently-used; 2 is least-recently-used
            cache.put(3, "three"); // must evict 2, not 1

            assertEquals(List.of(2), evicted);
            assertTrue(cache.get(1).isPresent());
            assertFalse(cache.get(2).isPresent());
            assertTrue(cache.get(3).isPresent());
            assertEquals(2, cache.size());
        }
    }

    @Test
    void lfuEvictsLeastFrequentlyUsedKeyWhenOverCapacity() {
        List<Object> evicted = new ArrayList<>();
        try (TtlCache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(2)
                .evictionPolicy(LfuEvictionPolicy::new)
                .addListener((key, value, cause) -> evicted.add(key))
                .build()) {

            cache.put("a", 1);
            cache.put("b", 2);
            cache.get("a");
            cache.get("a"); // "a" frequency = 3, "b" frequency = 1
            cache.put("c", 3); // must evict "b", the least-frequently-used

            assertEquals(List.of("b"), evicted);
            assertTrue(cache.get("a").isPresent());
            assertFalse(cache.get("b").isPresent());
            assertTrue(cache.get("c").isPresent());
        }
    }

    @Test
    void evictionNeverExceedsMaximumSizeUnderSequentialInserts() {
        try (TtlCache<Integer, String> cache = CacheBuilder.<Integer, String>newBuilder()
                .maximumSize(5)
                .build()) {

            for (int i = 0; i < 100; i++) {
                cache.put(i, "value-" + i);
                assertTrue(cache.size() <= 5, "size must never exceed maximumSize after a put settles");
            }
            assertEquals(5, cache.size());
        }
    }

    @Test
    void replacingAnExistingKeyReportsReplacedCauseNotSize() {
        List<EvictionCause> causes = new ArrayList<>();
        try (TtlCache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .maximumSize(10)
                .addListener((key, value, cause) -> causes.add(cause))
                .build()) {

            cache.put("k1", "v1");
            cache.put("k1", "v2"); // replace, well under capacity

            assertEquals(List.of(EvictionCause.REPLACED), causes);
        }
    }

    @Test
    void expiredEntryDoesNotCountTowardSizeEvictionDecisionIncorrectly() {
        // An entry that expired and was lazily removed via get() should free a capacity
        // slot for subsequent inserts rather than leaving a "ghost" over-capacity state.
        FakeClock clock = new FakeClock(0L);
        try (TtlCache<Integer, String> cache = CacheBuilder.<Integer, String>newBuilder()
                .maximumSize(2)
                .clock(clock)
                .reaperInterval(Duration.ofMinutes(10))
                .build()) {

            cache.put(1, "one", Duration.ofMillis(10));
            cache.put(2, "two");
            clock.advance(20);
            assertFalse(cache.get(1).isPresent()); // lazily expires and removes key 1

            cache.put(3, "three");

            assertTrue(cache.get(2).isPresent());
            assertTrue(cache.get(3).isPresent());
            assertEquals(2, cache.size());
        }
    }
}
