package lld.filesystem.exception;

/**
 * Base, unchecked exception type for all in-memory file-system failures.
 *
 * <p>Modeled as unchecked because almost every command (cp, mv, rm, ...) can fail for many
 * independent reasons (path missing, wrong type, name collision, cycle). Forcing callers to
 * declare/catch each one individually would pollute every method signature with little benefit;
 * the {@link lld.filesystem.command.Command} layer catches {@code FileSystemException} once and
 * converts it into a {@link lld.filesystem.command.CommandResult} failure, which is the only
 * place that needs to care.</p>
 */
public class FileSystemException extends RuntimeException {

    public FileSystemException(String message) {
        super(message);
    }

    public FileSystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
