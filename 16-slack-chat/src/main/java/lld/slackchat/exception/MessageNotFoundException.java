package lld.slackchat.exception;

/** Thrown when a referenced {@code MessageId} does not exist (or is not visible) in a channel. */
public class MessageNotFoundException extends ChatException {
    public MessageNotFoundException(String message) {
        super(message);
    }
}
