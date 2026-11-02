package lld.ttlcache.demo;

import lld.ttlcache.Cache;
import lld.ttlcache.CacheBuilder;
import lld.ttlcache.CacheStats;
import lld.ttlcache.policy.LfuEvictionPolicy;
import lld.ttlcache.policy.LruEvictionPolicy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runnable demonstration of the TTL cache's public API. Not a test (see {@code tests/}
 * for JUnit coverage) &mdash; this exists purely to show idiomatic usage end-to-end,
 * which is what an interviewer typically asks for near the end of a session ("can you
 * show me how someone would actually use this?").
 */
public final class CacheDemo {

    public static void main(String[] args) throws InterruptedException {
        demonstrateBasicTtlExpiry();
        demonstrateSizeBasedEvictionWithListener();
        demonstrateLfuPolicy();
        demonstrateComputeIfAbsentStampedeProtection();
    }

    private static void demonstrateBasicTtlExpiry() throws InterruptedException {
        System.out.println("\n=== Basic TTL expiry ===");
        try (Cache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .reaperInterval(Duration.ofMillis(50))
                .build()) {

            cache.put("session:42", "alice", Duration.ofMillis(150));
            System.out.println("Immediately after put: " + cache.get("session:42"));

            Thread.sleep(300);
            System.out.println("After TTL elapses: " + cache.get("session:42"));

            CacheStats stats = cache.stats();
            System.out.printf(
                    "stats -> hits=%d misses=%d expirations=%d hitRate=%.2f%n",
                    stats.hitCount(), stats.missCount(), stats.expirationCount(), stats.hitRate());
        }
    }

    private static void demonstrateSizeBasedEvictionWithListener() {
        System.out.println("\n=== Size-based LRU eviction with listener ===");
        try (Cache<Integer, String> cache = CacheBuilder.<Integer, String>newBuilder()
                .maximumSize(3)
                .evictionPolicy(LruEvictionPolicy::new)
                .addListener((key, value, cause) ->
                        System.out.println("  evicted key=" + key + " value=" + value + " cause=" + cause))
                .build()) {

            cache.put(1, "one");
            cache.put(2, "two");
            cache.put(3, "three");
            cache.get(1); // touch 1, so 2 becomes the least-recently-used
            cache.put(4, "four"); // should evict key 2, not key 1

            System.out.println("present 1? " + cache.get(1).isPresent());
            System.out.println("present 2? " + cache.get(2).isPresent());
            System.out.println("size=" + cache.size());
        }
    }

    private static void demonstrateLfuPolicy() {
        System.out.println("\n=== Size-based LFU eviction ===");
        try (Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
                .maximumSize(2)
                .evictionPolicy(LfuEvictionPolicy::new)
                .addListener((key, value, cause) ->
                        System.out.println("  evicted key=" + key + " cause=" + cause))
                .build()) {

            cache.put("a", 1);
            cache.put("b", 2);
            cache.get("a");
            cache.get("a"); // "a" now has frequency 3, "b" has frequency 1
            cache.put("c", 3); // "b" is least-frequently-used -> evicted

            System.out.println("present a? " + cache.get("a").isPresent());
            System.out.println("present b? " + cache.get("b").isPresent());
            System.out.println("present c? " + cache.get("c").isPresent());
        }
    }

    private static void demonstrateComputeIfAbsentStampedeProtection() throws InterruptedException {
        System.out.println("\n=== computeIfAbsent stampede protection ===");
        AtomicInteger loadCount = new AtomicInteger();
        try (Cache<String, String> cache = CacheBuilder.<String, String>newBuilder().build()) {
            int threadCount = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        cache.computeIfAbsent("expensive-key", k -> {
                            loadCount.incrementAndGet();
                            simulateSlowLoad();
                            return "computed-value";
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown(); // release all threads at once to maximize race likelihood
            done.await(5, TimeUnit.SECONDS);
            pool.shutdown();

            System.out.println(threadCount + " threads raced for the same missing key.");
            System.out.println("loader was invoked " + loadCount.get() + " time(s) (expected: 1).");
        }
    }

    private static void simulateSlowLoad() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
