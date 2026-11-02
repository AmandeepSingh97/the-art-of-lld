package lld.clouddrive.exception;

/**
 * Thrown for structurally invalid requests that are not access-control or quota failures,
 * e.g. moving a folder into its own descendant, uploading a new version to a folder,
 * or restoring an item that is not in the trash.
 */
public class InvalidOperationException extends DriveException {

    public InvalidOperationException(String message) {
        super(message);
    }
}
