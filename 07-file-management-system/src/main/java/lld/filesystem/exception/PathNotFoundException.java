package lld.filesystem.exception;

/** Thrown when a path cannot be resolved to any existing entry in the tree. */
public class PathNotFoundException extends FileSystemException {
    public PathNotFoundException(String path) {
        super("No such file or directory: " + path);
    }
}
