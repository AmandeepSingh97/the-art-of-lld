package lld.fooddelivery.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A customer's shopping cart, scoped to exactly one restaurant at a time (a common real-world
 * constraint: you cannot checkout items from two different kitchens in a single order/delivery
 * trip). Adding an item from a different restaurant than the current cart's restaurant clears
 * the cart first, mirroring Swiggy/Zomato UX ("starting a new cart will remove items from your
 * previous restaurant").
 *
 * <p><b>Thread-safety:</b> a cart is owned by exactly one customer and is only ever mutated by
 * that customer's own requests, so unlike {@link Order} or {@link DeliveryPartner} there is no
 * cross-actor contention on a single cart. We still guard mutation methods with
 * {@code synchronized} because a single customer may have multiple devices/tabs issuing
 * concurrent add/remove calls, and we want add-then-recompute-total to be atomic.
 */
public final class Cart {

    private final String customerId;
    private String restaurantId;
    private final Map<String, CartItem> items = new LinkedHashMap<>();

    public Cart(String customerId) {
        this.customerId = Objects.requireNonNull(customerId, "customerId");
    }

    public synchronized String restaurantId() {
        return restaurantId;
    }

    public synchronized void addItem(String restaurantId, MenuItem menuItem, int quantity) {
        Objects.requireNonNull(restaurantId, "restaurantId");
        Objects.requireNonNull(menuItem, "menuItem");
        if (this.restaurantId != null && !this.restaurantId.equals(restaurantId)) {
            // Switching restaurants starts a fresh cart, matching real-world product behaviour.
            items.clear();
        }
        this.restaurantId = restaurantId;
        CartItem existing = items.get(menuItem.id());
        if (existing != null) {
            existing.setQuantity(existing.quantity() + quantity);
        } else {
            items.put(menuItem.id(), new CartItem(menuItem.id(), menuItem.name(), menuItem.price(), quantity));
        }
    }

    public synchronized void updateQuantity(String menuItemId, int quantity) {
        CartItem item = items.get(menuItemId);
        if (item == null) {
            throw new IllegalArgumentException("Item not in cart: " + menuItemId);
        }
        if (quantity <= 0) {
            items.remove(menuItemId);
        } else {
            item.setQuantity(quantity);
        }
    }

    public synchronized void removeItem(String menuItemId) {
        items.remove(menuItemId);
    }

    public synchronized List<CartItem> items() {
        return List.copyOf(items.values());
    }

    public synchronized boolean isEmpty() {
        return items.isEmpty();
    }

    public synchronized void clear() {
        items.clear();
        restaurantId = null;
    }

    public synchronized Money subtotal() {
        Money total = Money.ZERO;
        for (CartItem item : items.values()) {
            total = total.add(item.lineTotal());
        }
        return total;
    }

    public String customerId() {
        return customerId;
    }
}
