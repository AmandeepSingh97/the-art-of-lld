package lld.clouddrive.exception;

/**
 * Base unchecked exception for all domain errors raised by the cloud drive system.
 *
 * <p>Kept unchecked deliberately: callers (REST controllers, CLI, demo code) are expected to
 * catch at a coarse boundary and translate to a transport-specific error (HTTP 4xx, gRPC status,
 * etc.) rather than being forced to declare {@code throws} on every service method.
 */
public class DriveException extends RuntimeException {

    public DriveException(String message) {
        super(message);
    }

    public DriveException(String message, Throwable cause) {
        super(message, cause);
    }
}
