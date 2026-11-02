package lld.slackchat.exception;

/** Thrown for structurally invalid channel operations, e.g. creating a DM with one member,
 * or joining a private channel without an invite. */
public class InvalidChannelOperationException extends ChatException {
    public InvalidChannelOperationException(String message) {
        super(message);
    }
}
