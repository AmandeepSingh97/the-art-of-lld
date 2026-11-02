package lld.fooddelivery.service;

import lld.fooddelivery.domain.MenuItem;
import lld.fooddelivery.domain.Restaurant;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry and mutation surface for {@link Restaurant}s and their menus.
 *
 * <p><b>Thread-safety:</b> restaurant registration uses a {@link ConcurrentHashMap} (safe
 * concurrent reads/writes without external locking); per-item availability mutation is
 * delegated straight through to {@link MenuItem}'s own atomic field, so this service never
 * itself needs a lock — it is a thin, stateless façade over thread-safe domain objects.
 */
public final class RestaurantService {

    private final ConcurrentHashMap<String, Restaurant> restaurants = new ConcurrentHashMap<>();

    public void registerRestaurant(Restaurant restaurant) {
        restaurants.put(restaurant.id(), restaurant);
    }

    public Optional<Restaurant> findRestaurant(String restaurantId) {
        return Optional.ofNullable(restaurants.get(restaurantId));
    }

    public Restaurant requireRestaurant(String restaurantId) {
        Restaurant restaurant = restaurants.get(restaurantId);
        if (restaurant == null) {
            throw new IllegalArgumentException("No such restaurant: " + restaurantId);
        }
        return restaurant;
    }

    public Collection<Restaurant> allRestaurants() {
        return restaurants.values();
    }

    public void setItemAvailability(String restaurantId, String itemId, boolean available) {
        requireRestaurant(restaurantId).menu().requireItem(itemId).setAvailable(available);
    }

    public void setAcceptingOrders(String restaurantId, boolean accepting) {
        requireRestaurant(restaurantId).setAcceptingOrders(accepting);
    }
}
