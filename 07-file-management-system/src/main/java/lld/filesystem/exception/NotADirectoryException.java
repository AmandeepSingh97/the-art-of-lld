package lld.filesystem.exception;

/** Thrown when an operation that requires a directory (cd, ls, mkdir parent, ...) finds a file. */
public class NotADirectoryException extends FileSystemException {
    public NotADirectoryException(String path) {
        super("Not a directory: " + path);
    }
}
