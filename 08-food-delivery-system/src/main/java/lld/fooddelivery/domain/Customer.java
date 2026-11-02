package lld.fooddelivery.domain;

import java.util.Objects;

/** A platform customer. Immutable identity fields; address/location can be updated per order. */
public final class Customer {

    private final String id;
    private final String name;
    private volatile GeoLocation defaultAddress;

    public Customer(String id, String name, GeoLocation defaultAddress) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.defaultAddress = Objects.requireNonNull(defaultAddress, "defaultAddress");
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public GeoLocation defaultAddress() {
        return defaultAddress;
    }

    public void updateDefaultAddress(GeoLocation location) {
        this.defaultAddress = Objects.requireNonNull(location, "location");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Customer)) return false;
        return id.equals(((Customer) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Customer{" + id + ", " + name + '}';
    }
}
