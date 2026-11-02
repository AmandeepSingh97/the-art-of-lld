package lld.fooddelivery.service;

import lld.fooddelivery.domain.Cart;
import lld.fooddelivery.domain.CartItem;
import lld.fooddelivery.domain.Order;
import lld.fooddelivery.domain.OrderItem;
import lld.fooddelivery.domain.OrderStatus;
import lld.fooddelivery.domain.Payment;
import lld.fooddelivery.domain.Restaurant;
import lld.fooddelivery.exception.EmptyCartException;
import lld.fooddelivery.exception.ItemUnavailableException;
import lld.fooddelivery.exception.OrderNotFoundException;
import lld.fooddelivery.exception.PaymentFailedException;
import lld.fooddelivery.exception.RestaurantNotAcceptingOrdersException;
import lld.fooddelivery.observer.OrderObserver;
import lld.fooddelivery.strategy.PriceCalculator;
import lld.fooddelivery.strategy.PricingContext;
import lld.fooddelivery.util.IdGenerator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the end-to-end order lifecycle: checkout from a {@link Cart}, payment, the
 * restaurant accept/reject-with-timeout window, kitchen progress, delivery-partner assignment,
 * and cancellation/refund. This is the one class in the system that is allowed to know about
 * every other service — it is a textbook Facade/orchestrator, deliberately kept thin: almost
 * every method is a short sequence of calls into {@link Order}, {@link RestaurantService},
 * {@link DeliveryAssignmentService}, and {@link PaymentService}, each of which owns its own
 * invariants and thread-safety. {@code OrderService} does not re-implement locking for
 * anything that the domain objects already guarantee for themselves.
 *
 * <p><b>Acceptance timeout:</b> every newly placed order schedules a timeout task on the
 * provided {@link ScheduledExecutorService}. If the restaurant has not accepted or rejected
 * the order within {@link #acceptanceTimeout} the order is auto-cancelled and refunded. The
 * scheduled task is cancelled whenever the restaurant responds first — see
 * {@link #acceptOrder} / {@link #rejectOrder}.
 */
public final class OrderService {

    private final RestaurantService restaurantService;
    private final CartService cartService;
    private final DeliveryAssignmentService deliveryAssignmentService;
    private final PaymentService paymentService;
    private final ScheduledExecutorService scheduler;
    private final Executor assignmentExecutor;
    private final Duration acceptanceTimeout;
    private final IdGenerator idGenerator = new IdGenerator("order");

    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> acceptanceTimeoutTasks = new ConcurrentHashMap<>();
    private final List<OrderObserver> defaultObservers = new ArrayList<>();

    public OrderService(RestaurantService restaurantService,
                         CartService cartService,
                         DeliveryAssignmentService deliveryAssignmentService,
                         PaymentService paymentService,
                         ScheduledExecutorService scheduler,
                         Executor assignmentExecutor,
                         Duration acceptanceTimeout) {
        this.restaurantService = restaurantService;
        this.cartService = cartService;
        this.deliveryAssignmentService = deliveryAssignmentService;
        this.paymentService = paymentService;
        this.scheduler = scheduler;
        this.assignmentExecutor = assignmentExecutor;
        this.acceptanceTimeout = acceptanceTimeout;
    }

    /** Observers (e.g. notification channels) attached to every order placed from now on. */
    public void registerDefaultObserver(OrderObserver observer) {
        defaultObservers.add(observer);
    }

    public Order findOrder(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }
        return order;
    }

    /**
     * Checks out the customer's current cart: re-validates item availability and restaurant
     * acceptance (the cart may have been built minutes ago), computes the final price via the
     * supplied {@link PriceCalculator} pipeline, charges payment, creates the order in
     * {@code PLACED} status, and schedules the restaurant-acceptance timeout.
     */
    public Order placeOrder(String customerId, PriceCalculator pricingPipeline,
                             boolean firstOrderForCustomer, double deliveryDistanceKm) {
        Cart cart = cartService.cartFor(customerId);
        if (cart.isEmpty()) {
            throw new EmptyCartException(customerId);
        }
        String restaurantId = cart.restaurantId();
        Restaurant restaurant = restaurantService.requireRestaurant(restaurantId);
        if (!restaurant.isAcceptingOrders()) {
            throw new RestaurantNotAcceptingOrdersException(restaurantId);
        }

        List<CartItem> cartItems = cart.items();
        // Re-validate availability at checkout time: an item could have gone out of stock
        // after being added to the cart but before checkout. This closes the race between
        // "add to cart" and "place order" that a single add-time check cannot close.
        for (CartItem cartItem : cartItems) {
            if (!restaurant.menu().isItemAvailable(cartItem.menuItemId())) {
                throw new ItemUnavailableException(cartItem.menuItemId(), cartItem.name());
            }
        }

        List<OrderItem> orderItems = new ArrayList<>();
        for (CartItem cartItem : cartItems) {
            orderItems.add(cartItem.toOrderItem());
        }

        var subtotal = cart.subtotal();
        var context = new PricingContext(subtotal, firstOrderForCustomer, deliveryDistanceKm);
        var total = pricingPipeline.calculate(context);

        Order order = new Order(idGenerator.next(), customerId, restaurantId, orderItems, subtotal, total);
        for (OrderObserver observer : defaultObservers) {
            order.addObserver(observer);
        }

        try {
            paymentService.charge(order);
        } catch (PaymentFailedException e) {
            // Never leave an unpaid, unusable order lying around for the restaurant to see.
            order.cancel("payment failed: " + e.getMessage());
            orders.put(order.id(), order);
            throw e;
        }

        orders.put(order.id(), order);
        cartService.clear(customerId);
        scheduleAcceptanceTimeout(order);
        return order;
    }

    private void scheduleAcceptanceTimeout(Order order) {
        ScheduledFuture<?> future = scheduler.schedule(
                () -> autoCancelIfStillUnanswered(order.id()),
                acceptanceTimeout.toMillis(), TimeUnit.MILLISECONDS);
        acceptanceTimeoutTasks.put(order.id(), future);
    }

    private void cancelAcceptanceTimeout(String orderId) {
        ScheduledFuture<?> future = acceptanceTimeoutTasks.remove(orderId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void autoCancelIfStillUnanswered(String orderId) {
        Order order = orders.get(orderId);
        if (order == null || order.status() != OrderStatus.PLACED) {
            return; // restaurant already responded, or order no longer exists
        }
        order.cancel("restaurant did not respond within the acceptance SLA");
        refundIfPaid(orderId);
    }

    public void acceptOrder(String orderId) {
        Order order = findOrder(orderId);
        cancelAcceptanceTimeout(orderId);
        order.accept();
        Restaurant restaurant = restaurantService.requireRestaurant(order.restaurantId());
        // Delivery-partner search happens asynchronously; it races (safely — see
        // DeliveryAssignmentService javadoc) against a possible concurrent rejection/cancel.
        assignmentExecutor.execute(() -> deliveryAssignmentService.assignPartnerFor(order, restaurant.location()));
    }

    public void rejectOrder(String orderId, String reason) {
        Order order = findOrder(orderId);
        cancelAcceptanceTimeout(orderId);
        order.reject(reason);
        refundIfPaid(orderId);
    }

    public void startPreparing(String orderId) {
        findOrder(orderId).startPreparing();
    }

    public void markReadyForPickup(String orderId) {
        findOrder(orderId).markReadyForPickup();
    }

    public void pickUp(String orderId) {
        findOrder(orderId).pickUp();
    }

    public void deliver(String orderId) {
        findOrder(orderId).deliver();
    }

    /**
     * Customer-initiated cancellation. The order's current {@code OrderState} is the single
     * source of truth for whether this is still allowed (only {@code PLACED}/{@code ACCEPTED});
     * {@link Order#cancel(String)} throws {@code InvalidOrderStateTransitionException} once the
     * kitchen has started preparing, which this method intentionally does not catch — the
     * caller (API layer) is expected to translate that into a "contact support" response.
     */
    public void cancelOrder(String orderId, String reason) {
        Order order = findOrder(orderId);
        cancelAcceptanceTimeout(orderId);
        order.cancel(reason);
        Optional.ofNullable(order.deliveryPartnerId()).ifPresent(deliveryAssignmentService::release);
        refundIfPaid(orderId);
    }

    private void refundIfPaid(String orderId) {
        Optional<Payment> payment = paymentService.paymentFor(orderId);
        payment.ifPresent(p -> paymentService.refund(orderId));
    }
}
