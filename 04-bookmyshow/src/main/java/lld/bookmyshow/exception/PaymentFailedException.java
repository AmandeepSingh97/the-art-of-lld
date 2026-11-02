package lld.bookmyshow.exception;

/**
 * Thrown by {@code PaymentGateway} implementations (or re-thrown by
 * {@code BookingService}) when a charge attempt is declined or errors out.
 * The booking workflow treats this as terminal for the current attempt: the
 * seat lock is released so the seats become bookable by someone else.
 */
public class PaymentFailedException extends RuntimeException {
    public PaymentFailedException(String message) {
        super(message);
    }
}
