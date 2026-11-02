package lld.fooddelivery.domain;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A restaurant partner on the platform. Owns a {@link Menu} and controls whether it is
 * currently accepting new orders (e.g. closed for the night, or temporarily paused due to
 * overload). {@code acceptingOrders} is a simple on/off switch independent of per-item
 * availability.
 */
public final class Restaurant {

    private final String id;
    private final String name;
    private final GeoLocation location;
    private final Menu menu;
    private final AtomicBoolean acceptingOrders = new AtomicBoolean(true);

    public Restaurant(String id, String name, GeoLocation location, Menu menu) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.location = Objects.requireNonNull(location, "location");
        this.menu = Objects.requireNonNull(menu, "menu");
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public GeoLocation location() {
        return location;
    }

    public Menu menu() {
        return menu;
    }

    public boolean isAcceptingOrders() {
        return acceptingOrders.get();
    }

    public void setAcceptingOrders(boolean value) {
        acceptingOrders.set(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Restaurant)) return false;
        return id.equals(((Restaurant) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Restaurant{" + id + ", " + name + '}';
    }
}
