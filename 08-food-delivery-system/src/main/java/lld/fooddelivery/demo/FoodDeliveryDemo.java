package lld.fooddelivery.demo;

import lld.fooddelivery.domain.DeliveryPartner;
import lld.fooddelivery.domain.GeoLocation;
import lld.fooddelivery.domain.Menu;
import lld.fooddelivery.domain.MenuItem;
import lld.fooddelivery.domain.Money;
import lld.fooddelivery.domain.Order;
import lld.fooddelivery.domain.PartnerStatus;
import lld.fooddelivery.domain.Restaurant;
import lld.fooddelivery.observer.CustomerNotificationObserver;
import lld.fooddelivery.observer.DeliveryPartnerNotificationObserver;
import lld.fooddelivery.observer.OrderTrackingObserver;
import lld.fooddelivery.observer.RestaurantDashboardObserver;
import lld.fooddelivery.service.CartService;
import lld.fooddelivery.service.DeliveryAssignmentService;
import lld.fooddelivery.service.OrderService;
import lld.fooddelivery.service.PaymentService;
import lld.fooddelivery.service.RestaurantService;
import lld.fooddelivery.strategy.BaseSubtotalCalculator;
import lld.fooddelivery.strategy.FlatDiscountDecorator;
import lld.fooddelivery.strategy.NearestAvailablePartnerStrategy;
import lld.fooddelivery.strategy.PercentageDiscountDecorator;
import lld.fooddelivery.strategy.PriceCalculator;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runnable end-to-end demonstration of the food-delivery system: restaurant/menu setup, cart
 * management, order placement with payment, restaurant acceptance, async delivery-partner
 * assignment, state progression to delivery, and a cancellation+refund scenario.
 *
 * <p>Run with {@code java -cp <classes> lld.fooddelivery.demo.FoodDeliveryDemo}.
 */
public final class FoodDeliveryDemo {

    public static void main(String[] args) throws InterruptedException {
        RestaurantService restaurantService = new RestaurantService();
        CartService cartService = new CartService(restaurantService);
        DeliveryAssignmentService deliveryAssignmentService =
                new DeliveryAssignmentService(new NearestAvailablePartnerStrategy());
        PaymentService paymentService = new PaymentService();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        OrderService orderService = new OrderService(
                restaurantService, cartService, deliveryAssignmentService, paymentService,
                scheduler, scheduler, Duration.ofSeconds(5));
        orderService.registerDefaultObserver(new CustomerNotificationObserver());
        orderService.registerDefaultObserver(new RestaurantDashboardObserver());
        orderService.registerDefaultObserver(new DeliveryPartnerNotificationObserver());
        OrderTrackingObserver trackingObserver = new OrderTrackingObserver();
        orderService.registerDefaultObserver(trackingObserver);

        // ---- Setup: restaurant with a menu ----
        GeoLocation restaurantLocation = new GeoLocation(12.9716, 77.5946);
        Menu menu = new Menu();
        MenuItem margherita = new MenuItem("item-1", "Margherita Pizza", Money.of("349.00"), "Pizza", true);
        MenuItem garlicBread = new MenuItem("item-2", "Garlic Bread", Money.of("149.00"), "Sides", true);
        menu.addItem(margherita);
        menu.addItem(garlicBread);
        Restaurant restaurant = new Restaurant("rest-1", "Tony's Pizzeria", restaurantLocation, menu);
        restaurantService.registerRestaurant(restaurant);

        // ---- Setup: delivery partners ----
        deliveryAssignmentService.registerPartner(
                new DeliveryPartner("rider-1", "Asha", new GeoLocation(12.9720, 77.5950), PartnerStatus.AVAILABLE));
        deliveryAssignmentService.registerPartner(
                new DeliveryPartner("rider-2", "Vikram", new GeoLocation(12.9900, 77.6100), PartnerStatus.AVAILABLE));

        // ---- Customer builds a cart ----
        String customerId = "cust-1";
        cartService.addItem(customerId, "rest-1", "item-1", 1);
        cartService.addItem(customerId, "rest-1", "item-2", 2);
        System.out.println("Cart subtotal: " + cartService.cartFor(customerId).subtotal());

        // ---- Pricing pipeline (Decorator): 15% off capped at ₹100, plus ₹50 off first order ----
        PriceCalculator pricing = new FlatDiscountDecorator(
                new PercentageDiscountDecorator(new BaseSubtotalCalculator(), 15, Money.of("100.00")),
                Money.of("50.00"), Money.of("200.00"), true);

        Order order = orderService.placeOrder(customerId, pricing, true, 3.2);
        System.out.println("Order placed: " + order + ", total=" + order.total());

        // ---- Happy path: restaurant accepts, prepares, delivers ----
        orderService.acceptOrder(order.id());
        Thread.sleep(200); // allow async delivery-partner assignment to complete
        System.out.println("Delivery partner assigned: " + order.deliveryPartnerId());

        orderService.startPreparing(order.id());
        orderService.markReadyForPickup(order.id());
        orderService.pickUp(order.id());
        orderService.deliver(order.id());
        System.out.println("Final status: " + order.status());

        System.out.println("\n--- Tracking timeline for " + order.id() + " ---");
        trackingObserver.timeline().forEach(System.out::println);

        // ---- Cancellation + refund scenario on a second order ----
        System.out.println("\n--- Cancellation scenario ---");
        cartService.addItem(customerId, "rest-1", "item-1", 1);
        Order secondOrder = orderService.placeOrder(customerId, new BaseSubtotalCalculator(), false, 1.0);
        System.out.println("Second order placed: " + secondOrder.id() + ", paid=" + paymentService.paymentFor(secondOrder.id()).orElseThrow());
        orderService.cancelOrder(secondOrder.id(), "customer changed their mind");
        System.out.println("Second order status: " + secondOrder.status()
                + ", refunded=" + paymentService.wasRefunded(secondOrder.id()));

        scheduler.shutdown();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);
    }
}
