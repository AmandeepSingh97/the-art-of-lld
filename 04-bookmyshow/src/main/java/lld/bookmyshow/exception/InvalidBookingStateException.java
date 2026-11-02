package lld.bookmyshow.exception;

/**
 * Thrown when an operation is attempted against a {@code Booking} that is
 * not in a state that permits it (e.g. cancelling an already-CANCELLED
 * booking, or confirming a booking whose lock already EXPIRED).
 */
public class InvalidBookingStateException extends RuntimeException {
    public InvalidBookingStateException(String message) {
        super(message);
    }
}
