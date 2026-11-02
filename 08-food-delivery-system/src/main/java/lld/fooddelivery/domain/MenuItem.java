package lld.fooddelivery.domain;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A sellable item on a restaurant's menu.
 *
 * <p><b>Thread-safety:</b> {@code MenuItem} is mutated concurrently by two independent
 * actors: the restaurant owner (toggling {@code available} when stock runs out) and the
 * order-placement path (reading {@code available} to validate a cart at checkout time).
 * {@link #available} is backed by an {@link AtomicBoolean} so that toggling and reading are
 * lock-free, visible across threads, and race-free — no two toggles can be lost, and a
 * concurrent read during a toggle always observes either the pre- or post-toggle value, never
 * a torn value. Price and name are treated as effectively immutable for the lifetime of an
 * in-flight order: an order captures a <em>snapshot</em> ({@link OrderItem}) of price/name at
 * checkout time so that a later menu price change never retroactively changes an existing
 * order's total.
 */
public final class MenuItem {

    private final String id;
    private final String name;
    private final Money price;
    private final String category;
    private final AtomicBoolean available;

    public MenuItem(String id, String name, Money price, String category, boolean available) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.price = Objects.requireNonNull(price, "price");
        this.category = Objects.requireNonNull(category, "category");
        this.available = new AtomicBoolean(available);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Money price() {
        return price;
    }

    public String category() {
        return category;
    }

    public boolean isAvailable() {
        return available.get();
    }

    public void setAvailable(boolean value) {
        available.set(value);
    }

    /**
     * Atomically transitions availability from {@code expected} to {@code update}.
     * Used by inventory-management flows that must not clobber a concurrent toggle
     * (compare-and-set semantics, no explicit lock required).
     */
    public boolean compareAndSetAvailable(boolean expected, boolean update) {
        return available.compareAndSet(expected, update);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MenuItem)) return false;
        MenuItem menuItem = (MenuItem) o;
        return id.equals(menuItem.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "MenuItem{" + id + ", " + name + ", " + price + ", available=" + available.get() + '}';
    }
}
