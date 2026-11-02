package lld.splitwise.exception;

/** Thrown when an operation references a group id that does not exist. */
public class GroupNotFoundException extends RuntimeException {
    public GroupNotFoundException(String groupId) {
        super("Group not found: " + groupId);
    }
}
