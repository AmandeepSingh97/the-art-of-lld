package lld.clouddrive.exception;

/** Thrown when creating/renaming/moving a node would create two siblings with the same name. */
public class DuplicateNameException extends DriveException {

    public DuplicateNameException(String parentId, String name) {
        super("Folder '" + parentId + "' already contains an entry named '" + name + "'");
    }
}
