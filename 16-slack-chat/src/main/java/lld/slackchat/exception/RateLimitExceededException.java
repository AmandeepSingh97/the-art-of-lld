package lld.slackchat.exception;

/** Thrown when a sender exceeds their allotted message-send rate (see {@code TokenBucketRateLimiter}). */
public class RateLimitExceededException extends ChatException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
