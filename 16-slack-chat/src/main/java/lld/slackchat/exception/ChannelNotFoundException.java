package lld.slackchat.exception;

/** Thrown when a referenced {@code ChannelId} does not exist in the channel repository. */
public class ChannelNotFoundException extends ChatException {
    public ChannelNotFoundException(String message) {
        super(message);
    }
}
