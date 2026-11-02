package lld.fooddelivery.service;

import lld.fooddelivery.domain.Cart;
import lld.fooddelivery.domain.MenuItem;
import lld.fooddelivery.domain.Restaurant;
import lld.fooddelivery.exception.ItemUnavailableException;
import lld.fooddelivery.exception.RestaurantNotAcceptingOrdersException;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages each customer's in-progress {@link Cart}.
 *
 * <p><b>Thread-safety:</b> one cart per customer id, stored in a {@link ConcurrentHashMap} and
 * created atomically via {@link ConcurrentHashMap#computeIfAbsent}. Mutation within a single
 * cart is guarded by {@link Cart}'s own {@code synchronized} methods (see {@link Cart}
 * javadoc) — this service does not need its own additional locking.
 */
public final class CartService {

    private final ConcurrentHashMap<String, Cart> cartsByCustomerId = new ConcurrentHashMap<>();
    private final RestaurantService restaurantService;

    public CartService(RestaurantService restaurantService) {
        this.restaurantService = restaurantService;
    }

    public Cart cartFor(String customerId) {
        return cartsByCustomerId.computeIfAbsent(customerId, Cart::new);
    }

    /**
     * Adds an item to the customer's cart after validating that the restaurant is currently
     * accepting orders and the item is currently available. Note this is a <em>point-in-time</em>
     * check: availability is re-validated again at checkout time in
     * {@code OrderService#placeOrder} because the time between "add to cart" and "place order"
     * can be minutes, during which the kitchen may run out of the item — see README
     * "Concurrency and Thread Safety" for why a single check here would not be sufficient.
     */
    public void addItem(String customerId, String restaurantId, String menuItemId, int quantity) {
        Restaurant restaurant = restaurantService.requireRestaurant(restaurantId);
        if (!restaurant.isAcceptingOrders()) {
            throw new RestaurantNotAcceptingOrdersException(restaurantId);
        }
        MenuItem menuItem = restaurant.menu().requireItem(menuItemId);
        if (!menuItem.isAvailable()) {
            throw new ItemUnavailableException(menuItem.id(), menuItem.name());
        }
        cartFor(customerId).addItem(restaurantId, menuItem, quantity);
    }

    public void removeItem(String customerId, String menuItemId) {
        cartFor(customerId).removeItem(menuItemId);
    }

    public void clear(String customerId) {
        cartFor(customerId).clear();
    }
}
