package lld.bookmyshow.model;

import java.util.Objects;

/**
 * A city in which theatres operate. Immutable value-ish entity identified by
 * {@code id}.
 */
public final class City {
    private final String id;
    private final String name;

    public City(String id, String name) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof City city)) return false;
        return id.equals(city.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "City{id='" + id + "', name='" + name + "'}";
    }
}
