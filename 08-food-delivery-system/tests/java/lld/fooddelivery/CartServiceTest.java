package lld.fooddelivery;

import lld.fooddelivery.domain.Menu;
import lld.fooddelivery.domain.MenuItem;
import lld.fooddelivery.domain.Money;
import lld.fooddelivery.domain.Restaurant;
import lld.fooddelivery.domain.GeoLocation;
import lld.fooddelivery.exception.ItemUnavailableException;
import lld.fooddelivery.service.CartService;
import lld.fooddelivery.service.RestaurantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates cart management behaviour, including the race between a customer checking out and
 * a restaurant concurrently marking the same item unavailable.
 */
class CartServiceTest {

    private RestaurantService restaurantService;
    private CartService cartService;
    private MenuItem pizza;

    @BeforeEach
    void setUp() {
        restaurantService = new RestaurantService();
        cartService = new CartService(restaurantService);
        Menu menu = new Menu();
        pizza = new MenuItem("item-1", "Margherita", Money.of("300.00"), "Pizza", true);
        menu.addItem(pizza);
        Restaurant restaurant = new Restaurant("rest-1", "Tony's", new GeoLocation(1, 1), menu);
        restaurantService.registerRestaurant(restaurant);
    }

    @Test
    void addingItemsAccumulatesQuantity() {
        cartService.addItem("cust-1", "rest-1", "item-1", 1);
        cartService.addItem("cust-1", "rest-1", "item-1", 2);
        assertEquals(3, cartService.cartFor("cust-1").items().get(0).quantity());
        assertEquals(Money.of("900.00"), cartService.cartFor("cust-1").subtotal());
    }

    @Test
    void addingAnUnavailableItemIsRejectedImmediately() {
        pizza.setAvailable(false);
        assertThrows(ItemUnavailableException.class, () -> cartService.addItem("cust-1", "rest-1", "item-1", 1));
    }

    @Test
    void switchingRestaurantsClearsThePreviousCart() {
        Menu otherMenu = new Menu();
        MenuItem burger = new MenuItem("item-2", "Burger", Money.of("150.00"), "Burgers", true);
        otherMenu.addItem(burger);
        restaurantService.registerRestaurant(new Restaurant("rest-2", "Burger Joint", new GeoLocation(2, 2), otherMenu));

        cartService.addItem("cust-1", "rest-1", "item-1", 1);
        cartService.addItem("cust-1", "rest-2", "item-2", 1);

        assertEquals("rest-2", cartService.cartFor("cust-1").restaurantId());
        assertEquals(1, cartService.cartFor("cust-1").items().size());
        assertEquals("item-2", cartService.cartFor("cust-1").items().get(0).menuItemId());
    }

    /**
     * Repeatedly toggles an item's availability on one thread while many threads attempt to
     * add it to their carts on another. No add-to-cart call may ever observe a "torn" read
     * (neither true nor false) of availability, and the system must never throw anything other
     * than the expected {@link ItemUnavailableException} for a momentarily-false read.
     */
    @Test
    void concurrentAvailabilityToggleNeverCorruptsReads() throws InterruptedException {
        int readers = 16;
        int iterationsPerReader = 200;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        AtomicInteger unexpectedErrors = new AtomicInteger(0);
        CountDownLatch done = new CountDownLatch(readers);
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);

        pool.submit(() -> {
            while (!stop.get()) {
                pizza.setAvailable(!pizza.isAvailable());
            }
        });

        for (int r = 0; r < readers; r++) {
            final String customerId = "reader-" + r;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < iterationsPerReader; i++) {
                        try {
                            cartService.addItem(customerId, "rest-1", "item-1", 1);
                        } catch (ItemUnavailableException expected) {
                            // fine: the toggler made it unavailable at read time
                        } catch (RuntimeException unexpected) {
                            unexpectedErrors.incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(10, TimeUnit.SECONDS));
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, unexpectedErrors.get(), "no unexpected exception types should occur under concurrent toggling");
    }
}
