package lld.bookmyshow.exception;

/**
 * Thrown when a caller attempts to confirm or extend a seat lock
 * ({@code SeatLock}) whose TTL has already elapsed. The seats have — or are
 * about to be — swept back to {@code AVAILABLE} by the expiry reaper.
 */
public class SeatLockExpiredException extends RuntimeException {
    public SeatLockExpiredException(String message) {
        super(message);
    }
}
