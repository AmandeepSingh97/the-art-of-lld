package lld.clouddrive.exception;

/**
 * Thrown when a user attempts an operation on a node for which their effective role
 * (own ACL entry, inherited ACL entry, or ownership) does not satisfy the minimum role
 * required for that operation.
 */
public class AccessDeniedException extends DriveException {

    public AccessDeniedException(String userId, String nodeId, String requiredRole) {
        super("User '" + userId + "' does not have at least '" + requiredRole
                + "' permission on node '" + nodeId + "'");
    }
}
