package lld.clouddrive.exception;

/**
 * Thrown when a user attempts an upload (new file or new version) that would push their
 * consumed storage past their allotted quota.
 *
 * <p>Carries the requested bytes and the bytes actually available so callers (and tests) can
 * assert on the exact numbers rather than just the fact that it failed.
 */
public class QuotaExceededException extends DriveException {

    private final long requestedBytes;
    private final long availableBytes;

    public QuotaExceededException(String userId, long requestedBytes, long availableBytes) {
        super("User '" + userId + "' quota exceeded: requested " + requestedBytes
                + " bytes but only " + availableBytes + " bytes available");
        this.requestedBytes = requestedBytes;
        this.availableBytes = availableBytes;
    }

    public long getRequestedBytes() {
        return requestedBytes;
    }

    public long getAvailableBytes() {
        return availableBytes;
    }
}
