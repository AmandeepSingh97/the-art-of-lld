package lld.fooddelivery.domain;

import java.util.Objects;

/**
 * An immutable snapshot of a menu item captured at checkout time and frozen onto an
 * {@link Order}. Immutability here is a deliberate domain invariant: once an order is placed,
 * its price must never drift because the restaurant later changes a menu price — the customer
 * was quoted (and pays) the price at the moment of order placement.
 */
public final class OrderItem {

    private final String menuItemId;
    private final String name;
    private final Money unitPriceAtOrderTime;
    private final int quantity;

    public OrderItem(String menuItemId, String name, Money unitPriceAtOrderTime, int quantity) {
        this.menuItemId = Objects.requireNonNull(menuItemId, "menuItemId");
        this.name = Objects.requireNonNull(name, "name");
        this.unitPriceAtOrderTime = Objects.requireNonNull(unitPriceAtOrderTime, "unitPriceAtOrderTime");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        this.quantity = quantity;
    }

    public String menuItemId() {
        return menuItemId;
    }

    public String name() {
        return name;
    }

    public Money unitPriceAtOrderTime() {
        return unitPriceAtOrderTime;
    }

    public int quantity() {
        return quantity;
    }

    public Money lineTotal() {
        return unitPriceAtOrderTime.multiply(quantity);
    }

    @Override
    public String toString() {
        return quantity + "x " + name + " @ " + unitPriceAtOrderTime;
    }
}
