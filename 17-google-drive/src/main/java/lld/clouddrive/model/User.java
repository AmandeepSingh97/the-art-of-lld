package lld.clouddrive.model;

import java.util.Objects;

/**
 * A registered drive user. Deliberately does NOT carry quota usage counters -- that mutable,
 * highly-contended state lives in {@code QuotaManager} so that locking/atomicity concerns around
 * quota are localized to one component instead of being smeared across the User object (see
 * README "Trade-offs" section).
 */
public final class User {

    private final String id;
    private final String displayName;
    private final String email;

    public User(String id, String displayName, String email) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.email = Objects.requireNonNull(email, "email");
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User)) return false;
        return id.equals(((User) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "User{" + id + ", " + displayName + "}";
    }
}
