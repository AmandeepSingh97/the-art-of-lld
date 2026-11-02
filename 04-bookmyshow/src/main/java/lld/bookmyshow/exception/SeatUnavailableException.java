package lld.bookmyshow.exception;

/**
 * Thrown when one or more requested seats cannot be locked because they are
 * already {@code LOCKED} (held by another user) or {@code BOOKED}.
 *
 * <p>This is a recoverable, expected condition under contention — callers
 * (API layer) should map it to HTTP 409 Conflict, not 500.
 */
public class SeatUnavailableException extends RuntimeException {
    public SeatUnavailableException(String message) {
        super(message);
    }
}
