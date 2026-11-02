package lld.slackchat.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 1.0);

        assertTrue(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("alice"));
        assertFalse(limiter.tryAcquire("alice"), "4th immediate request should exceed the bucket capacity");
    }

    @Test
    void refillsOverTimeAllowingMoreRequestsLater() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 20.0); // ~50ms per token

        assertTrue(limiter.tryAcquire("bob"));
        assertFalse(limiter.tryAcquire("bob"), "bucket should be empty immediately after being drained");

        Thread.sleep(150); // well over one refill interval at 20 tokens/sec

        assertTrue(limiter.tryAcquire("bob"), "bucket should have refilled at least one token by now");
    }

    @Test
    void oneUsersConsumptionDoesNotAffectAnotherUsersBucket() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1.0);

        assertTrue(limiter.tryAcquire("noisy-user"));
        assertFalse(limiter.tryAcquire("noisy-user"));

        assertTrue(limiter.tryAcquire("quiet-user"), "a different key must have its own independent bucket");
    }

    @Test
    void rejectsInvalidConfiguration() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 1.0));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new TokenBucketRateLimiter(5, 0.0));
    }
}
