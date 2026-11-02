package lld.filesystem.exception;

/** Thrown when removing a non-empty directory without the recursive flag. */
public class DirectoryNotEmptyException extends FileSystemException {
    public DirectoryNotEmptyException(String path) {
        super("Directory not empty: " + path);
    }
}
