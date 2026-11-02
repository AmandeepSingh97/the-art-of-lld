package lld.filesystem.exception;

/** Thrown on a name collision where the caller did not opt into overwrite/merge semantics. */
public class FileAlreadyExistsException extends FileSystemException {
    public FileAlreadyExistsException(String path) {
        super("File exists: " + path);
    }
}
