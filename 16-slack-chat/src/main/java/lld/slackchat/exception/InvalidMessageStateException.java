package lld.slackchat.exception;

/** Thrown when an operation is attempted against a message in an incompatible state, e.g.
 * editing a message that has already been deleted. */
public class InvalidMessageStateException extends ChatException {
    public InvalidMessageStateException(String message) {
        super(message);
    }
}
