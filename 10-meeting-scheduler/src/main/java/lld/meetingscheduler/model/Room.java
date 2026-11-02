package lld.meetingscheduler.model;

import java.time.ZoneId;
import java.util.Objects;

/** A bookable physical/virtual resource (conference room, desk, projector, etc.). */
public final class Room {

    private final RoomId id;
    private final String name;
    private final int capacity;
    private final String location;
    private final ZoneId timezone;

    public Room(RoomId id, String name, int capacity, String location, ZoneId timezone) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.location = Objects.requireNonNull(location, "location must not be null");
        this.timezone = Objects.requireNonNull(timezone, "timezone must not be null");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
    }

    public RoomId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int capacity() {
        return capacity;
    }

    public String location() {
        return location;
    }

    public ZoneId timezone() {
        return timezone;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Room room)) return false;
        return id.equals(room.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Room{id=" + id + ", name='" + name + "', capacity=" + capacity + "}";
    }
}
