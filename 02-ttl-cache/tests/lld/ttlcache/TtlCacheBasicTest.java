package lld.ttlcache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core functional behaviour: put/get, absence, TTL expiry (lazy, via the deterministic
 * {@link FakeClock}), invalidate, and basic validation.
 */
final class TtlCacheBasicTest {

    @Test
    void getOnAbsentKeyReturnsEmptyAndRecordsMiss() {
        try (TtlCache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .reaperInterval(Duration.ofMinutes(10))
                .build()) {

            assertEquals(Optional.empty(), cache.get("missing"));
            assertEquals(1, cache.stats().missCount());
            assertEquals(0, cache.stats().hitCount());
        }
    }

    @Test
    void putThenGetReturnsTheStoredValueAndRecordsHit() {
        try (TtlCache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .reaperInterval(Duration.ofMinutes(10))
                .build()) {

            cache.put("k1", "v1");

            assertEquals(Optional.of("v1"), cache.get("k1"));
            assertEquals(1, cache.stats().hitCount());
        }
    }

    @Test
    void putReplacesExistingValueForSameKey() {
        try (TtlCache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .reaperInterval(Duration.ofMinutes(10))
                .build()) {

            cache.put("k1", "v1");
            cache.put("k1", "v2");

            assertEquals(Optional.of("v2"), cache.get("k1"));
            assertEquals(1, cache.size());
        }
    }

    @Test
    void entryExpiresLazilyOnGetAfterTtlElapses() {
        FakeClock clock = new FakeClock(0L);
        try (TtlCache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .clock(clock)
                .reaperInterval(Duration.ofMinutes(10)) // effectively disable active reaping for this test
                .build()) {

            cache.put("k1", "v1", Duration.ofMillis(100));
            assertEquals(Optional.of("v1"), cache.get("k1"));

            clock.advance(101);

            assertEquals(Optional.empty(), cache.get("k1"), "entry must not be returned once its TTL has elapsed");
            assertEquals(1, cache.stats().expirationCount());
            // The lazy expiry path must physically remove the dead entry, not just hide it.
            assertEquals(0, cache.size());
        }
    }

    @Test
    void entryWithoutExplicitTtlNeverExpires() {
        FakeClock clock = new FakeClock(0L);
        try (TtlCache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .clock(clock)
                .reaperInterval(Duration.ofMinutes(10))
                .build()) {

            cache.put("k1", "v1"); // defaultTtl is Duration.ZERO => never expires
            clock.advance(Duration.ofDays(365).toMillis());

            assertEquals(Optional.of("v1"), cache.get("k1"));
        }
    }

    @Test
    void backgroundReaperActivelyRemovesExpiredEntriesWithoutAnyGetCall() throws InterruptedException {
        try (TtlCache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .reaperInterval(Duration.ofMillis(20))
                .build()) {

            cache.put("k1", "v1", Duration.ofMillis(10));
            assertEquals(1, cache.size());

            // Give the real-time reaper a few sweep cycles to run without ever calling get().
            Thread.sleep(300);

            assertEquals(0, cache.size(), "reaper should have actively evicted the expired entry");
            assertTrue(cache.stats().expirationCount() >= 1);
        }
    }

    @Test
    void invalidateRemovesEntryAndNotifiesListenerWithExplicitCause() {
        StringBuilder observed = new StringBuilder();
        try (TtlCache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .reaperInterval(Duration.ofMinutes(10))
                .addListener((key, value, cause) -> observed.append(key).append(':').append(cause))
                .build()) {

            cache.put("k1", "v1");
            cache.invalidate("k1");

            assertEquals(Optional.empty(), cache.get("k1"));
            assertEquals("k1:EXPLICIT", observed.toString());
        }
    }

    @Test
    void invalidateAllClearsEveryEntry() {
        try (TtlCache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .reaperInterval(Duration.ofMinutes(10))
                .build()) {

            cache.put("k1", "v1");
            cache.put("k2", "v2");
            cache.invalidateAll();

            assertEquals(0, cache.size());
            assertFalse(cache.get("k1").isPresent());
            assertFalse(cache.get("k2").isPresent());
        }
    }

    @Test
    void getRejectsNullKey() {
        try (TtlCache<String, String> cache = CacheBuilder.<String, String>newBuilder().build()) {
            assertThrows(NullPointerException.class, () -> cache.get(null));
        }
    }

    @Test
    void putRejectsNullValue() {
        try (TtlCache<String, String> cache = CacheBuilder.<String, String>newBuilder().build()) {
            assertThrows(NullPointerException.class, () -> cache.put("k1", null));
        }
    }
}
