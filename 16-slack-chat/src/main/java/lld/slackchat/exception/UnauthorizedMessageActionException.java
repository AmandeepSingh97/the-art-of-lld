package lld.slackchat.exception;

/** Thrown when a user attempts to edit or delete a message authored by someone else. */
public class UnauthorizedMessageActionException extends ChatException {
    public UnauthorizedMessageActionException(String message) {
        super(message);
    }
}
