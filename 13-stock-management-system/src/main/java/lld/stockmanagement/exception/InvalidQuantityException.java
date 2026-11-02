package lld.stockmanagement.exception;

/** Thrown when a caller passes a zero or negative quantity to an operation requiring a positive one. */
public class InvalidQuantityException extends RuntimeException {
    public InvalidQuantityException(String message) {
        super(message);
    }
}
