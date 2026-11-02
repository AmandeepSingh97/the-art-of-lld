package lld.fooddelivery.domain;

import java.util.Objects;

/**
 * A mutable line item inside an in-progress {@link Cart}. Unlike {@link OrderItem} (an
 * immutable post-checkout snapshot), a {@code CartItem} tracks a live reference to the menu
 * item id/name/price at add-time but quantity can still change before checkout.
 */
public final class CartItem {

    private final String menuItemId;
    private final String name;
    private final Money unitPrice;
    private volatile int quantity;

    public CartItem(String menuItemId, String name, Money unitPrice, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        this.menuItemId = Objects.requireNonNull(menuItemId, "menuItemId");
        this.name = Objects.requireNonNull(name, "name");
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
        this.quantity = quantity;
    }

    public String menuItemId() {
        return menuItemId;
    }

    public String name() {
        return name;
    }

    public Money unitPrice() {
        return unitPrice;
    }

    public int quantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        this.quantity = quantity;
    }

    public Money lineTotal() {
        return unitPrice.multiply(quantity);
    }

    public OrderItem toOrderItem() {
        return new OrderItem(menuItemId, name, unitPrice, quantity);
    }
}
