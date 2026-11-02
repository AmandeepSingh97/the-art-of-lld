package lld.ttlcache;

/**
 * The reason an entry left the cache, surfaced to {@link CacheEvictionListener}s.
 *
 * <p>Distinguishing the cause matters operationally: a high {@code SIZE} eviction rate
 * signals an under-provisioned cache (raise {@code maximumSize}), whereas a high
 * {@code EXPIRED} rate is expected/benign for a TTL-based design. Conflating the two in a
 * single "removed" callback would hide this signal from operators.
 */
public enum EvictionCause {

    /** The entry's TTL elapsed; removed either lazily (on access) or by the background reaper. */
    EXPIRED,

    /** The cache was at {@code maximumSize} and the eviction policy chose this entry to make room. */
    SIZE,

    /** A caller explicitly called {@code invalidate(key)} or {@code invalidateAll()}. */
    EXPLICIT,

    /** The key already existed and {@code put} replaced its value; the old value is reported as "evicted". */
    REPLACED
}
