package lld.fooddelivery.domain;

import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A restaurant's catalogue of {@link MenuItem}s.
 *
 * <p><b>Thread-safety:</b> backed by a {@link ConcurrentHashMap} so that concurrent reads
 * (customers browsing/checking out) never block writers (restaurant updating the menu), and
 * concurrent structural writes (adding/removing items) are internally striped/safe without an
 * explicit lock. Individual item availability toggles are delegated to {@link MenuItem}'s own
 * atomic field — {@code Menu} itself never needs to lock for a single-item availability flip.
 */
public final class Menu {

    private final ConcurrentHashMap<String, MenuItem> items = new ConcurrentHashMap<>();

    public void addItem(MenuItem item) {
        items.put(item.id(), item);
    }

    public void removeItem(String itemId) {
        items.remove(itemId);
    }

    public Optional<MenuItem> findItem(String itemId) {
        return Optional.ofNullable(items.get(itemId));
    }

    public Collection<MenuItem> allItems() {
        return items.values();
    }

    /** Convenience used by order placement to re-validate availability at checkout time. */
    public boolean isItemAvailable(String itemId) {
        MenuItem item = items.get(itemId);
        return item != null && item.isAvailable();
    }

    public MenuItem requireItem(String itemId) {
        MenuItem item = items.get(itemId);
        if (item == null) {
            throw new NoSuchElementException("No such menu item: " + itemId);
        }
        return item;
    }
}
