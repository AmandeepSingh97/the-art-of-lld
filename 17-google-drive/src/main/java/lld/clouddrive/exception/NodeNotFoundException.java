package lld.clouddrive.exception;

/** Thrown when a file/folder id does not resolve to a live (non-purged) node. */
public class NodeNotFoundException extends DriveException {

    public NodeNotFoundException(String nodeId) {
        super("No node found with id '" + nodeId + "'");
    }
}
