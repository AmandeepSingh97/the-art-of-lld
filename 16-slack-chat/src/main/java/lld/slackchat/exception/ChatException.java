package lld.slackchat.exception;

/** Base, unchecked exception type for all chat-domain errors. */
public class ChatException extends RuntimeException {

    public ChatException(String message) {
        super(message);
    }

    public ChatException(String message, Throwable cause) {
        super(message, cause);
    }
}
