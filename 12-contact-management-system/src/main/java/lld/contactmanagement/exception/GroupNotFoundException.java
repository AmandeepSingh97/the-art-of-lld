package lld.contactmanagement.exception;

import java.util.UUID;

/** Thrown when an operation references a group id that does not exist. */
public class GroupNotFoundException extends RuntimeException {

    public GroupNotFoundException(UUID groupId) {
        super("No group found with id " + groupId);
    }

    public GroupNotFoundException(String message) {
        super(message);
    }
}
