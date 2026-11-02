package lld.bookmyshow.exception;

/** Thrown when a lookup references a {@code Booking} id that does not exist. */
public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(String message) {
        super(message);
    }
}
