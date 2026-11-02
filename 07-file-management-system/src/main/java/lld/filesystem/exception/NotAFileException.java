package lld.filesystem.exception;

/** Thrown when an operation that requires a regular file (cat, write) finds a directory. */
public class NotAFileException extends FileSystemException {
    public NotAFileException(String path) {
        super("Not a file (is a directory): " + path);
    }
}
