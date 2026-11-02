package lld.lfucache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CacheFactory}: each factory method must produce a cache
 * with the right policy, thread-safety, and instrumentation behavior, and
 * {@link CacheFactory#instrumentedThreadSafeLfuCache} in particular must
 * combine both cross-cutting behaviors correctly (this is the composition
 * most likely to be wired in the wrong order, so it gets its own dedicated
 * assertions rather than relying on the simpler factory methods' tests to
 * imply it works).
 */
class CacheFactoryTest {

    @Test
    void lfuCacheEvictsByFrequency() {
        Cache<String, Integer> cache = CacheFactory.lfuCache(1);
        cache.put("a", 1);
        cache.put("b", 2);

        assertTrue(cache.get("a").isEmpty());
        assertEquals(1, cache.size());
    }

    @Test
    void threadSafeLfuCacheIsSafeUnderConcurrentAccess() throws InterruptedException {
        Cache<Integer, Integer> cache = CacheFactory.threadSafeLfuCache(10);
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int base = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 2_000; i++) {
                        cache.put((base + i) % 15, i);
                        cache.get((base + i) % 15);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(8, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(cache.size() <= cache.capacity());
    }

    @Test
    void instrumentedLfuCacheTracksStats() {
        StatsRecordingCache<String, Integer> cache = CacheFactory.instrumentedLfuCache(5);

        cache.put("a", 1);
        cache.get("a");
        cache.get("missing");

        assertEquals(1, cache.stats().hitCount());
        assertEquals(1, cache.stats().missCount());
    }

    @Test
    void instrumentedThreadSafeLfuCacheCountsEvictionsAndIsConcurrencySafe() throws InterruptedException {
        StatsRecordingCache<Integer, Integer> cache = CacheFactory.instrumentedThreadSafeLfuCache(5);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 3_000; i++) {
                        cache.put(i % 20, i); // key space (20) > capacity (5): guarantees evictions
                        cache.get(i % 20);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(8, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue(cache.size() <= cache.capacity());
        assertTrue(cache.stats().evictionCount() > 0,
                "expected evictions to be observed and counted when key space exceeds capacity");
        assertTrue(cache.stats().requestCount() > 0);
    }
}
