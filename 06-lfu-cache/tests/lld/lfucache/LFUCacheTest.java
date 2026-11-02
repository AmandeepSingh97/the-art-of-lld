package lld.lfucache;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core correctness tests for {@link LFUCache}: basic get/put, the LFU
 * eviction policy, the LRU tie-break rule among equal frequencies, value
 * updates, boundary capacities, and negative/invalid input handling.
 */
class LFUCacheTest {

    @Test
    void getOnEmptyCacheReturnsEmpty() {
        LFUCache<String, String> cache = new LFUCache<>(2);

        assertTrue(cache.get("missing").isEmpty());
    }

    @Test
    void putThenGetReturnsStoredValue() {
        LFUCache<String, Integer> cache = new LFUCache<>(2);

        cache.put("a", 1);

        assertEquals(Optional.of(1), cache.get("a"));
        assertEquals(1, cache.size());
    }

    @Test
    void puttingExistingKeyUpdatesValueWithoutChangingSize() {
        LFUCache<String, Integer> cache = new LFUCache<>(2);
        cache.put("a", 1);

        cache.put("a", 99);

        assertEquals(Optional.of(99), cache.get("a"));
        assertEquals(1, cache.size());
    }

    @Test
    void evictsLeastFrequentlyUsedEntryWhenFull() {
        LFUCache<String, String> cache = new LFUCache<>(2);
        cache.put("A", "apple");
        cache.put("B", "banana");

        cache.get("A"); // A now has frequency 2, B still has frequency 1

        cache.put("C", "cherry"); // must evict B, the sole minimum-frequency entry

        assertTrue(cache.get("B").isEmpty(), "B should have been evicted as the LFU entry");
        assertEquals(Optional.of("apple"), cache.get("A"));
        assertEquals(Optional.of("cherry"), cache.get("C"));
        assertEquals(2, cache.size());
    }

    @Test
    void tiesAtSameFrequencyAreBrokenByLeastRecentlyUsed() {
        LFUCache<Integer, String> cache = new LFUCache<>(2);
        cache.put(1, "one"); // freq 1
        cache.put(2, "two"); // freq 1; both keys now tied at frequency 1,
                              // with key 1 being the less-recently-touched

        cache.put(3, "three"); // must evict key 1, not key 2

        assertTrue(cache.get(1).isEmpty(), "key 1 is the LRU entry among frequency-1 ties");
        assertEquals(Optional.of("two"), cache.get(2));
        assertEquals(Optional.of("three"), cache.get(3));
    }

    @Test
    void repeatedAccessProtectsAnEntryFromEviction() {
        LFUCache<String, Integer> cache = new LFUCache<>(2);
        cache.put("hot", 1);
        cache.put("cold", 2);

        // Access "hot" many times so it is never the minimum frequency again.
        for (int i = 0; i < 10; i++) {
            cache.get("hot");
        }
        cache.put("new1", 3); // evicts "cold" (freq 1, loses to "hot" at freq 11+)
        cache.put("new2", 4); // evicts "new1" (freq 1), never touches "hot"

        assertEquals(Optional.of(1), cache.get("hot"));
        assertTrue(cache.get("cold").isEmpty());
        assertTrue(cache.get("new1").isEmpty());
        assertEquals(Optional.of(4), cache.get("new2"));
    }

    @Test
    void zeroCapacityCacheNeverStoresAnything() {
        LFUCache<String, Integer> cache = new LFUCache<>(0);

        cache.put("a", 1);

        assertTrue(cache.get("a").isEmpty());
        assertEquals(0, cache.size());
        assertEquals(0, cache.capacity());
    }

    @Test
    void capacityOneAlwaysEvictsThePreviousEntryOnAnyNewKey() {
        LFUCache<String, Integer> cache = new LFUCache<>(1);

        cache.put("a", 1);
        cache.get("a");
        cache.get("a"); // frequency now high, but capacity 1 means it's always the minimum too
        cache.put("b", 2);

        assertTrue(cache.get("a").isEmpty());
        assertEquals(Optional.of(2), cache.get("b"));
        assertEquals(1, cache.size());
    }

    @Test
    void negativeCapacityThrowsInvalidCacheCapacityException() {
        assertThrows(InvalidCacheCapacityException.class, () -> new LFUCache<Object, Object>(-1));
    }

    @Test
    void evictionListenerIsNotifiedWithEvictedKeyAndValue() {
        StringBuilder observedEvictions = new StringBuilder();
        LFUCache<String, String> cache = new LFUCache<>(1,
                (key, value) -> observedEvictions.append(key).append("=").append(value).append(";"));

        cache.put("a", "1");
        cache.put("b", "2"); // evicts a=1

        assertEquals("a=1;", observedEvictions.toString());
    }

    @Test
    void evictionListenerIsNotInvokedForPlainValueUpdates() {
        StringBuilder observedEvictions = new StringBuilder();
        LFUCache<String, String> cache = new LFUCache<>(2,
                (key, value) -> observedEvictions.append(key).append(';'));

        cache.put("a", "1");
        cache.put("a", "2"); // update, not an eviction

        assertEquals("", observedEvictions.toString());
    }

    @Test
    void sizeNeverExceedsCapacityAcrossManyInsertions() {
        LFUCache<Integer, Integer> cache = new LFUCache<>(16);

        for (int i = 0; i < 500; i++) {
            cache.put(i, i);
            assertFalse(cache.size() > cache.capacity(),
                    "size must never exceed capacity, violated at insertion " + i);
        }
        assertEquals(16, cache.size());
    }
}
