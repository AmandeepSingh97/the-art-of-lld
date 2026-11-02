package lld.lfucache;

/**
 * Runnable demonstration of {@link LFUCache} covering: basic get/put,
 * frequency-based eviction, the LRU tie-break among equal frequencies, the
 * thread-safe decorator, and the instrumented decorator. Intended to be the
 * first thing a reviewer runs ({@code java -cp out lld.lfucache.LFUCacheDemo})
 * to sanity-check behavior against the README's walkthroughs.
 */
public final class LFUCacheDemo {

    private LFUCacheDemo() {
    }

    public static void main(String[] args) {
        basicEvictionWalkthrough();
        tieBreakWalkthrough();
        instrumentedWalkthrough();
        threadSafeWalkthrough();
    }

    private static void basicEvictionWalkthrough() {
        System.out.println("=== Basic LFU eviction ===");
        Cache<String, String> cache = CacheFactory.lfuCache(2);

        cache.put("A", "apple");
        cache.put("B", "banana");
        System.out.println("get(A) -> " + cache.get("A")); // A freq=2, B freq=1
        cache.put("C", "cherry"); // capacity exceeded, evict B (freq=1, only one at min)
        System.out.println("get(B) after eviction -> " + cache.get("B")); // empty
        System.out.println("get(A) -> " + cache.get("A"));
        System.out.println("get(C) -> " + cache.get("C"));
        System.out.println();
    }

    private static void tieBreakWalkthrough() {
        System.out.println("=== LRU tie-break within equal frequency ===");
        Cache<Integer, String> cache = CacheFactory.lfuCache(2);

        cache.put(1, "one");
        cache.put(2, "two");
        // Both keys are at frequency 1. put(3,..) must evict the LRU of the
        // two: key 1 (inserted first, touched least recently).
        cache.put(3, "three");
        System.out.println("get(1) -> " + cache.get(1)); // empty: evicted
        System.out.println("get(2) -> " + cache.get(2)); // two
        System.out.println("get(3) -> " + cache.get(3)); // three
        System.out.println();
    }

    private static void instrumentedWalkthrough() {
        System.out.println("=== Instrumented cache ===");
        StatsRecordingCache<String, Integer> cache = CacheFactory.instrumentedLfuCache(1);

        cache.put("x", 1);
        cache.get("x");       // hit
        cache.get("missing"); // miss
        cache.put("y", 2);    // evicts "x" (only entry, capacity 1)
        System.out.println("stats -> " + cache.stats());
        System.out.println();
    }

    private static void threadSafeWalkthrough() {
        System.out.println("=== Thread-safe cache under concurrent load ===");
        Cache<Integer, Integer> cache = CacheFactory.threadSafeLfuCache(100);
        int threadCount = 8;
        int opsPerThread = 2_000;
        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int base = t * opsPerThread;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    int key = (base + i) % 50; // overlapping key space on purpose
                    cache.put(key, key * key);
                    cache.get(key);
                }
            });
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while joining demo threads", e);
            }
        }
        System.out.println("Final size (<= capacity 100): " + cache.size());
    }
}
