package lld.slackchat.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classic Token Bucket rate limiter, used to throttle a single noisy sender without affecting
 * any other user.
 *
 * <p>Each key gets its own {@link Bucket} holding {@code capacity} tokens that refill
 * continuously at {@code refillTokensPerSecond}. A send consumes one token; if none are
 * available the caller is rejected. Token Bucket (over e.g. a fixed window counter) was chosen
 * because it tolerates legitimate bursts (a user pasting 5 quick messages) while still
 * bounding sustained throughput, and refill is computed lazily from elapsed wall-clock time
 * instead of needing a background ticking thread -- see README section 10 for the comparison
 * against sliding-window and leaky-bucket alternatives.
 *
 * <p><b>Thread-safety:</b> buckets are created lazily via
 * {@link ConcurrentHashMap#computeIfAbsent}, so bucket creation itself is safe and happens at
 * most once per key. Each individual {@link Bucket}'s refill-then-consume sequence is a
 * compound read-modify-write (not expressible as a single atomic CAS over two fields:
 * available tokens and last-refill timestamp), so it is guarded by a lock scoped to that one
 * bucket object -- contention is therefore limited to concurrent sends from the *same* user,
 * which is both rare (one human rarely sends two messages at the exact same nanosecond) and
 * exactly the scenario the rate limiter exists to serialize correctly anyway.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final int capacity;
    private final double refillTokensPerSecond;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, double refillTokensPerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        if (refillTokensPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "refillTokensPerSecond must be positive, got " + refillTokensPerSecond);
        }
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
    }

    @Override
    public boolean tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        return bucket.tryConsume(refillTokensPerSecond, capacity);
    }

    /** Mutable per-key token bucket; all access is synchronized on the instance. */
    private static final class Bucket {
        private double availableTokens;
        private long lastRefillNanos;

        Bucket(int capacity) {
            this.availableTokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume(double refillTokensPerSecond, int capacity) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                availableTokens = Math.min(capacity, availableTokens + elapsedSeconds * refillTokensPerSecond);
                lastRefillNanos = now;
            }
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
