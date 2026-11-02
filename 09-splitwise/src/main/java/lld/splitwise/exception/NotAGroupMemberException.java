package lld.splitwise.exception;

/** Thrown when an expense/settlement references a user who is not a member of the target group. */
public class NotAGroupMemberException extends RuntimeException {
    public NotAGroupMemberException(String userId, String groupId) {
        super("User %s is not a member of group %s".formatted(userId, groupId));
    }
}
