package lld.bookmyshow.exception;

/** Thrown when a lookup references a {@code Show} id that does not exist. */
public class ShowNotFoundException extends RuntimeException {
    public ShowNotFoundException(String message) {
        super(message);
    }
}
