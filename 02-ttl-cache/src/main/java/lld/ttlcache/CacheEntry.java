package lld.ttlcache;

/**
 * Internal, immutable holder for a cached value plus its absolute expiry timestamp.
 *
 * <p><b>Why immutable:</b> instances are published to other threads exclusively through
 * a {@code java.util.concurrent.ConcurrentHashMap}, whose happens-before guarantees mean
 * that any thread reading a {@code CacheEntry} out of the map is guaranteed to see a
 * fully-constructed, consistent object (value and expiry together) &mdash; there is no
 * window where one thread observes a value written by one thread and an expiry timestamp
 * written by another. If this were instead a mutable entry with a settable expiry field,
 * updating TTL in place would require either an extra lock per entry or careful use of
 * {@code volatile}, and would reintroduce exactly the kind of torn-read race this design
 * avoids: "replace the whole entry" is a strictly simpler and safer model than "mutate the
 * entry in place" for a structure shared across threads without a lock.
 *
 * <p>Package-private: this is a storage-layer detail of {@link TtlCache}, never exposed
 * to callers.
 *
 * @param <V> value type
 */
final class CacheEntry<V> {

    /** Sentinel expiry meaning "never expires" (used for entries inserted with a zero/none TTL). */
    static final long NO_EXPIRY = Long.MAX_VALUE;

    private final V value;
    private final long expireAtMillis;

    CacheEntry(V value, long expireAtMillis) {
        this.value = value;
        this.expireAtMillis = expireAtMillis;
    }

    V value() {
        return value;
    }

    /**
     * @param nowMillis current time as reported by the cache's {@link Clock}
     * @return {@code true} if this entry's TTL has elapsed as of {@code nowMillis}
     */
    boolean isExpiredAt(long nowMillis) {
        return expireAtMillis != NO_EXPIRY && nowMillis >= expireAtMillis;
    }
}
