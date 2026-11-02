package lld.meetingscheduler.model;

import java.time.ZoneId;
import java.util.Objects;

/**
 * A calendar user. Holds the user's home timezone purely for presentation purposes
 * (converting stored {@link java.time.Instant}s back to a local wall-clock time when
 * displaying a meeting to this user); all storage and comparison remains UTC-based.
 */
public final class User {

    private final UserId id;
    private final String name;
    private final String email;
    private final ZoneId timezone;

    public User(UserId id, String name, String email, ZoneId timezone) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.email = Objects.requireNonNull(email, "email must not be null");
        this.timezone = Objects.requireNonNull(timezone, "timezone must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    public UserId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String email() {
        return email;
    }

    public ZoneId timezone() {
        return timezone;
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
        return "User{id=" + id + ", name='" + name + "'}";
    }
}
