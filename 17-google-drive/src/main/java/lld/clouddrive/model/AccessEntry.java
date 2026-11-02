package lld.clouddrive.model;

import java.util.Objects;

/**
 * An explicit grant of {@code role} to {@code userId} on whichever node holds this entry.
 * Immutable value object; "changing" a grant means replacing the entry in the owning node's ACL.
 */
public final class AccessEntry {

    private final String userId;
    private final Role role;

    public AccessEntry(String userId, Role role) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.role = Objects.requireNonNull(role, "role");
    }

    public String getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AccessEntry)) return false;
        AccessEntry that = (AccessEntry) o;
        return userId.equals(that.userId) && role == that.role;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, role);
    }

    @Override
    public String toString() {
        return userId + ":" + role;
    }
}
