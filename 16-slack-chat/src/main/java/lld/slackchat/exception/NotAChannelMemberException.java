package lld.slackchat.exception;

/**
 * Thrown when a user attempts a channel-scoped action (send message, read history, mark read)
 * without being a member of that channel. This is the core authorization invariant of the
 * system -- see README section 5.
 */
public class NotAChannelMemberException extends ChatException {
    public NotAChannelMemberException(String message) {
        super(message);
    }
}
