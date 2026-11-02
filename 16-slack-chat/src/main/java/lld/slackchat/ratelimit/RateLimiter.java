package lld.slackchat.ratelimit;

/**
 * Per-key rate limiter contract. Keyed by an arbitrary string (here: a user id's string form)
 * so the same limiter instance can police many independent senders.
 */
public interface RateLimiter {

    /**
     * Attempts to consume one permit for {@code key}.
     *
     * @return {@code true} if the action is allowed (a permit was available), {@code false} if
     *         the caller is currently rate-limited.
     */
    boolean tryAcquire(String key);
}
