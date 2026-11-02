package lld.lfucache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency tests for {@link ConcurrentLFUCache}.
 *
 * <p>These tests cannot prove the absence of races the way a type system
 * proves type safety, but they are designed to make the specific failure
 * modes of an <em>unprotected</em> {@link LFUCache} likely to surface if the
 * locking were ever accidentally removed: many threads hammering a small,
 * overlapping key space so that frequency-bucket migrations collide.
 *
 * <p>A true "negative control" — running the same workload directly against
 * a bare, unsynchronized {@link LFUCache} and asserting it misbehaves — was
 * considered and deliberately rejected here: whether a data race manifests
 * as an observable symptom within a bounded number of iterations is
 * inherently timing-/scheduler-/JIT-dependent, so asserting "the race must
 * trigger" would make this suite flaky by construction. That experiment is
 * documented instead in {@code STAFF_LEVEL_DEEP_DIVE.md} as a manual
 * exercise, where a human can read nondeterministic output without failing a
 * CI run on a given machine's thread scheduling.
 */
class ConcurrentLFUCacheTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void sizeNeverExceedsCapacityUnderConcurrentPuts() throws InterruptedException {
        int capacity = 20;
        ConcurrentLFUCache<Integer, Integer> cache = new ConcurrentLFUCache<>(new LFUCache<>(capacity));
        int threadCount = 16;
        int opsPerThread = 5_000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicBoolean sizeInvariantViolated = new AtomicBoolean(false);

        for (int t = 0; t < threadCount; t++) {
            final int base = t;
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        int key = (base * 31 + i) % 50; // small, overlapping key space
                        cache.put(key, key);
                        cache.get(key);
                        if (cache.size() > cache.capacity()) {
                            sizeInvariantViolated.set(true);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(8, TimeUnit.SECONDS), "workload did not finish in time");
        executor.shutdown();

        assertFalse(sizeInvariantViolated.get(), "size exceeded capacity under concurrent load");
        assertTrue(cache.size() <= capacity);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void noExceptionsEscapeUnderHighContentionMixedReadWrite() throws InterruptedException {
        ConcurrentLFUCache<Integer, String> cache = new ConcurrentLFUCache<>(new LFUCache<>(8));
        int threadCount = 12;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 10_000; i++) {
                        int key = i % 10;
                        if (i % 3 == 0) {
                            cache.put(key, "value-" + key);
                        } else {
                            cache.get(key);
                        }
                    }
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(8, TimeUnit.SECONDS), "workload did not finish in time");
        executor.shutdown();
        assertEquals(0, failures.get(), "no thread should observe an exception from the shared cache");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void capacityAccessorNeedsNoLockAndReflectsConstructionValue() {
        ConcurrentLFUCache<Integer, Integer> cache = new ConcurrentLFUCache<>(new LFUCache<>(42));

        assertEquals(42, cache.capacity());
    }
}
