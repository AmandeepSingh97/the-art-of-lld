package lld.snakeandladder.exceptions;

/**
 * Thrown when a {@code Board} is constructed with an internally inconsistent
 * configuration: out-of-range cells, duplicate jump start cells, a jump that
 * degenerates to a no-op, or a cycle among jumps that would make resolution
 * non-terminating.
 *
 * <p>This is a construction-time (fail-fast) exception. Once a {@code Board}
 * is built, it is guaranteed to be internally consistent for the lifetime of
 * the object, so no caller ever needs to catch this exception after
 * {@code BoardBuilder.build()} has returned successfully.</p>
 */
public class InvalidBoardConfigurationException extends RuntimeException {

    public InvalidBoardConfigurationException(String message) {
        super(message);
    }
}
