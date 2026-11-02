package lld.filesystem.exception;

/**
 * Thrown for structurally invalid requests that are not simple "not found" / "wrong type"
 * problems, e.g. moving a directory into its own descendant (would create a cycle), moving the
 * root, or renaming an entry to "." or "..".
 */
public class InvalidOperationException extends FileSystemException {
    public InvalidOperationException(String message) {
        super(message);
    }
}
