package lld.ttlcache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency correctness tests. These assert the specific guarantees called out in
 * {@link TtlCache}'s class javadoc: no lost updates, no over-capacity cache under
 * concurrent writers, and exactly-once loading under a {@code computeIfAbsent} stampede.
 *
 * <p>These are necessarily probabilistic in the sense that they cannot force a specific
 * interleaving, but they use a high thread count and a shared starting gate
 * ({@link CountDownLatch}) to maximize the chance of exposing a race if one exists, and
 * they assert invariants that must hold regardless of interleaving (rather than timing-
 * dependent outcomes).
 */
final class TtlCacheConcurrencyTest {

    @Test
    void computeIfAbsentInvokesLoaderExactlyOnceUnderConcurrentStampede() throws InterruptedException {
        try (TtlCache<String, String> cache = CacheBuilder.<String, String>newBuilder().build()) {
            int threadCount = 64;
            AtomicInteger loadInvocations = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            Set<String> observedValues = ConcurrentHashMap.newKeySet();

            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    awaitUninterruptibly(start);
                    String value = cache.computeIfAbsent("stampede-key", k -> {
                        loadInvocations.incrementAndGet();
                        return "loaded-once";
                    });
                    observedValues.add(value);
                    done.countDown();
                });
            }

            ready.await();
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "all threads must finish within the timeout");
            pool.shutdown();

            assertEquals(1, loadInvocations.get(), "loader must be invoked exactly once across all racing threads");
            assertEquals(Set.of("loaded-once"), observedValues, "every thread must observe the single loaded value");
        }
    }

    @Test
    void concurrentGetAndPutAcrossManyKeysProducesNoLostUpdatesAndConsistentStats() throws InterruptedException {
        try (TtlCache<Integer, Integer> cache = CacheBuilder.<Integer, Integer>newBuilder().build()) {
            int threadCount = 32;
            int opsPerThread = 2_000;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch done = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            int key = (threadIndex * opsPerThread + i) % 500;
                            cache.put(key, key);
                            cache.get(key);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(30, TimeUnit.SECONDS), "all threads must finish within the timeout");
            pool.shutdown();

            // Every key in [0, 500) was put with value == key; no concurrent put on a
            // disjoint key should ever corrupt another key's stored value (no lost
            // updates / no torn writes across unrelated keys).
            for (int key = 0; key < 500; key++) {
                final int expectedValue = key;
                cache.get(key).ifPresent(value -> assertEquals(expectedValue, value));
            }

            CacheStats stats = cache.stats();
            assertEquals(stats.hitCount() + stats.missCount(), stats.requestCount());
            assertTrue(stats.hitCount() > 0, "concurrent gets should have produced at least some hits");
        }
    }

    @Test
    void concurrentPutsNeverLeaveCacheMeaningfullyOverCapacity() throws InterruptedException {
        try (TtlCache<Integer, String> cache = CacheBuilder.<Integer, String>newBuilder()
                .maximumSize(50)
                .build()) {
            int threadCount = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch done = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < 500; i++) {
                            int key = threadIndex * 10_000 + i;
                            cache.put(key, "v" + key);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(30, TimeUnit.SECONDS));
            pool.shutdown();

            // A brief, bounded overshoot during concurrent bursts is an accepted trade-off
            // (see TtlCache javadoc), but the cache must never run away unbounded.
            assertTrue(
                    cache.size() <= 50 + threadCount,
                    "cache size " + cache.size() + " grew unboundedly instead of converging toward maximumSize");
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
