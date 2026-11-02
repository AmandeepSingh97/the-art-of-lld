package lld.fooddelivery;

import lld.fooddelivery.domain.DeliveryPartner;
import lld.fooddelivery.domain.GeoLocation;
import lld.fooddelivery.domain.Menu;
import lld.fooddelivery.domain.MenuItem;
import lld.fooddelivery.domain.Money;
import lld.fooddelivery.domain.Order;
import lld.fooddelivery.domain.OrderStatus;
import lld.fooddelivery.domain.PartnerStatus;
import lld.fooddelivery.domain.Restaurant;
import lld.fooddelivery.exception.EmptyCartException;
import lld.fooddelivery.exception.InvalidOrderStateTransitionException;
import lld.fooddelivery.exception.ItemUnavailableException;
import lld.fooddelivery.service.CartService;
import lld.fooddelivery.service.DeliveryAssignmentService;
import lld.fooddelivery.service.OrderService;
import lld.fooddelivery.service.PaymentService;
import lld.fooddelivery.service.RestaurantService;
import lld.fooddelivery.strategy.BaseSubtotalCalculator;
import lld.fooddelivery.strategy.NearestAvailablePartnerStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end integration tests exercising {@link OrderService} against the full service graph. */
class OrderServiceIntegrationTest {

    private RestaurantService restaurantService;
    private CartService cartService;
    private DeliveryAssignmentService deliveryAssignmentService;
    private PaymentService paymentService;
    private ScheduledExecutorService scheduler;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        restaurantService = new RestaurantService();
        cartService = new CartService(restaurantService);
        deliveryAssignmentService = new DeliveryAssignmentService(new NearestAvailablePartnerStrategy());
        paymentService = new PaymentService();
        scheduler = Executors.newScheduledThreadPool(2);
        orderService = new OrderService(restaurantService, cartService, deliveryAssignmentService,
                paymentService, scheduler, scheduler, Duration.ofSeconds(5));

        Menu menu = new Menu();
        menu.addItem(new MenuItem("item-1", "Pizza", Money.of("300.00"), "Pizza", true));
        Restaurant restaurant = new Restaurant("rest-1", "Tony's", new GeoLocation(12.97, 77.59), menu);
        restaurantService.registerRestaurant(restaurant);
        deliveryAssignmentService.registerPartner(
                new DeliveryPartner("rider-1", "Asha", new GeoLocation(12.971, 77.591), PartnerStatus.AVAILABLE));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        scheduler.shutdownNow();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void placingAnOrderWithAnEmptyCartFails() {
        assertThrows(EmptyCartException.class, () -> orderService.placeOrder("cust-1", new BaseSubtotalCalculator(), false, 1.0));
    }

    @Test
    void fullHappyPathFromCartToDelivery() throws InterruptedException {
        cartService.addItem("cust-1", "rest-1", "item-1", 1);
        Order order = orderService.placeOrder("cust-1", new BaseSubtotalCalculator(), false, 1.0);
        assertEquals(OrderStatus.PLACED, order.status());
        assertTrue(cartService.cartFor("cust-1").isEmpty(), "cart must be cleared after checkout");

        orderService.acceptOrder(order.id());
        assertEquals(OrderStatus.ACCEPTED, order.status());

        // Give the async assignment task a moment to run.
        Thread.sleep(200);
        assertEquals("rider-1", order.deliveryPartnerId());
        assertEquals(PartnerStatus.BUSY, deliveryAssignmentService.find("rider-1").orElseThrow().status());

        orderService.startPreparing(order.id());
        orderService.markReadyForPickup(order.id());
        orderService.pickUp(order.id());
        orderService.deliver(order.id());
        assertEquals(OrderStatus.DELIVERED, order.status());
    }

    @Test
    void checkoutRevalidatesItemAvailabilityEvenIfAddedWhileAvailable() {
        cartService.addItem("cust-1", "rest-1", "item-1", 1);
        restaurantService.setItemAvailability("rest-1", "item-1", false);
        assertThrows(ItemUnavailableException.class,
                () -> orderService.placeOrder("cust-1", new BaseSubtotalCalculator(), false, 1.0));
    }

    @Test
    void rejectingAnOrderReleasesPaymentAsRefund() {
        cartService.addItem("cust-1", "rest-1", "item-1", 1);
        Order order = orderService.placeOrder("cust-1", new BaseSubtotalCalculator(), false, 1.0);
        orderService.rejectOrder(order.id(), "kitchen overloaded");
        assertEquals(OrderStatus.REJECTED, order.status());
        assertTrue(paymentService.wasRefunded(order.id()));
    }

    @Test
    void cancellingAfterPreparingStartedIsRejectedByTheOrderService() {
        cartService.addItem("cust-1", "rest-1", "item-1", 1);
        Order order = orderService.placeOrder("cust-1", new BaseSubtotalCalculator(), false, 1.0);
        orderService.acceptOrder(order.id());
        orderService.startPreparing(order.id());
        assertThrows(InvalidOrderStateTransitionException.class, () -> orderService.cancelOrder(order.id(), "too slow"));
    }

    @Test
    void cancellingAfterPartnerAssignmentReleasesThePartnerBackToThePool() throws InterruptedException {
        cartService.addItem("cust-1", "rest-1", "item-1", 1);
        Order order = orderService.placeOrder("cust-1", new BaseSubtotalCalculator(), false, 1.0);
        orderService.acceptOrder(order.id());
        Thread.sleep(200);
        assertEquals(PartnerStatus.BUSY, deliveryAssignmentService.find("rider-1").orElseThrow().status());

        orderService.cancelOrder(order.id(), "customer changed their mind");

        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(PartnerStatus.AVAILABLE, deliveryAssignmentService.find("rider-1").orElseThrow().status(),
                "the delivery partner must be released back to the pool, not stranded as BUSY");
        assertTrue(paymentService.wasRefunded(order.id()));
    }

    @Test
    void orderIsAutoCancelledAndRefundedIfRestaurantNeverResponds() throws InterruptedException {
        OrderService fastTimeoutOrderService = new OrderService(restaurantService, cartService,
                deliveryAssignmentService, paymentService, scheduler, scheduler, Duration.ofMillis(100));

        cartService.addItem("cust-1", "rest-1", "item-1", 1);
        Order order = fastTimeoutOrderService.placeOrder("cust-1", new BaseSubtotalCalculator(), false, 1.0);
        assertEquals(OrderStatus.PLACED, order.status());

        Thread.sleep(500);

        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals("restaurant did not respond within the acceptance SLA", order.terminationReason());
        assertTrue(paymentService.wasRefunded(order.id()));
    }

    @Test
    void acceptingAfterTimeoutTaskWasScheduledCancelsTheTimeoutSoNoLateAutoCancelOccurs() throws InterruptedException {
        OrderService fastTimeoutOrderService = new OrderService(restaurantService, cartService,
                deliveryAssignmentService, paymentService, scheduler, scheduler, Duration.ofMillis(150));

        cartService.addItem("cust-1", "rest-1", "item-1", 1);
        Order order = fastTimeoutOrderService.placeOrder("cust-1", new BaseSubtotalCalculator(), false, 1.0);
        fastTimeoutOrderService.acceptOrder(order.id());

        Thread.sleep(400); // well past the acceptance timeout window

        assertEquals(OrderStatus.ACCEPTED, order.status(), "an already-accepted order must not be auto-cancelled later");
    }
}
