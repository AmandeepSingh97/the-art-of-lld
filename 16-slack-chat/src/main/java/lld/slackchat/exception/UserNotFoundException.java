package lld.slackchat.exception;

/** Thrown when a referenced {@code UserId} does not exist in the user repository. */
public class UserNotFoundException extends ChatException {
    public UserNotFoundException(String message) {
        super(message);
    }
}
