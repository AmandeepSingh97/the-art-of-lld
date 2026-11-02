package lld.contactmanagement.exception;

/**
 * Thrown when a contact fails a structural or business-level validation
 * rule (e.g. blank display name, no contact point). Distinguished from
 * {@link IllegalArgumentException} so callers can catch domain-specific
 * validation failures without accidentally swallowing unrelated programming
 * errors (null pointers passed where an argument was required, etc.).
 */
public class InvalidContactException extends RuntimeException {

    public InvalidContactException(String message) {
        super(message);
    }

    public InvalidContactException(String message, Throwable cause) {
        super(message, cause);
    }
}
