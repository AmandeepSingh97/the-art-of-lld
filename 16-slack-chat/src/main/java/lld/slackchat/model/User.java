package lld.slackchat.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A registered user of the chat system.
 *
 * <p>{@code User} is intentionally a simple, mostly-immutable data holder: identity
 * ({@link #id()}, {@link #username()}) never changes after construction, while
 * {@code displayName} may be updated. Presence is deliberately <b>not</b> stored on
 * {@code User} -- it is tracked centrally by {@code PresenceService} because presence changes
 * far more frequently than identity and has different concurrency/broadcast needs (see
 * README section 11).
 */
public final class User {

    private final UserId id;
    private final String username;
    private volatile String displayName;
    private final Instant createdAt;

    public User(UserId id, String username, String displayName, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.username = validateUsername(username);
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String validateUsername(String username) {
        Objects.requireNonNull(username, "username");
        if (username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        return username;
    }

    public UserId id() {
        return id;
    }

    /** Immutable handle used for {@code @mentions}, e.g. {@code "jsmith"}. */
    public String username() {
        return username;
    }

    public String displayName() {
        return displayName;
    }

    public void renameTo(String newDisplayName) {
        this.displayName = Objects.requireNonNull(newDisplayName, "newDisplayName");
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof User user)) {
            return false;
        }
        return id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "User{id=" + id + ", username='" + username + "'}";
    }
}
