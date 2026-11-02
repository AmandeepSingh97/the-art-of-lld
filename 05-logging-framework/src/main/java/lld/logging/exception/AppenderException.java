package lld.logging.exception;

/**
 * Unchecked exception raised when an {@link lld.logging.appender.Appender}
 * cannot write/initialize/close its destination (e.g. underlying I/O
 * failure). Kept unchecked because appender failures are typically either
 * (a) handled by an internal fallback/error policy at the point of failure,
 * or (b) genuinely unrecoverable for that appender and should not force
 * every caller up the stack to declare a checked exception for a cross-
 * cutting concern like logging.
 */
public class AppenderException extends RuntimeException {
    public AppenderException(String message, Throwable cause) {
        super(message, cause);
    }

    public AppenderException(String message) {
        super(message);
    }
}
