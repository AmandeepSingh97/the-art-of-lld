package lld.splitwise.model;

import java.util.Objects;

/**
 * A registered user of the expense-sharing system.
 *
 * <p>Identity is defined solely by {@link #id()} (a stable surrogate key), so
 * {@code equals}/{@code hashCode} are based only on {@code id}. Name/email are
 * mutable-looking fields on purpose kept {@code final} here: in this in-memory
 * LLD scope a user's profile is created once and not updated; a production
 * system would route profile edits through {@code UserService} and keep this
 * object immutable per-version (copy-on-write) to avoid shared-mutable-state bugs.
 */
public final class User {

    private final String id;
    private final String name;
    private final String email;

    public User(String id, String name, String email) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.email = Objects.requireNonNull(email, "email");
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String email() {
        return email;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User user)) return false;
        return id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "User{id='%s', name='%s'}".formatted(id, name);
    }
}
