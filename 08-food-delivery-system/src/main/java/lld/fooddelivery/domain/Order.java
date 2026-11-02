package lld.fooddelivery.domain;

import lld.fooddelivery.observer.OrderObserver;
import lld.fooddelivery.state.OrderState;
import lld.fooddelivery.state.PlacedState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

/**
 * Aggregate root for a single food-delivery order. Combines:
 * <ul>
 *   <li>an immutable snapshot of what was ordered and at what price ({@link OrderItem}s,
 *       {@link Money} totals) — see {@link lld.fooddelivery.strategy.PriceCalculator};</li>
 *   <li>a mutable lifecycle driven by the State pattern ({@link OrderState});</li>
 *   <li>an Observer-pattern publish point for real-time tracking
 *       ({@link lld.fooddelivery.observer.OrderObserver}).</li>
 * </ul>
 *
 * <p><b>Thread-safety — the core concurrency guarantee of this class:</b> every method that
 * reads-then-writes {@link #state} or {@link #deliveryPartnerId} is {@code synchronized} on
 * this order's own intrinsic monitor. This is deliberately a single lock per order (not one
 * lock for "status" and a separate lock for "delivery partner") because the two are causally
 * linked: whether a delivery-partner assignment is allowed to succeed depends on the current
 * status, so checking the status and mutating the partner assignment must be one atomic
 * critical section.
 *
 * <p>Note that {@code reject()} is only legal from {@code PLACED} (a restaurant declines
 * before ever committing to the order), while delivery-partner assignment only becomes legal
 * once the order reaches {@code ACCEPTED} — so "reject" and "assign a partner" never actually
 * race against each other in this model; by the time a partner search can start, rejection is
 * no longer reachable. The transition that genuinely races with partner assignment is
 * {@code cancel()}, which (unlike {@code reject}) remains legal through {@code ACCEPTED}, i.e.
 * exactly the window during which assignment runs. Concretely, this is what the shared monitor
 * prevents:
 *
 * <pre>
 * Thread A (restaurant/customer cancels)  Thread B (delivery-assignment service)
 * ---------------------------------------  -------------------------------------
 * order.cancel("out of stock")              order.assignDeliveryPartner("rider-7")
 *   synchronized(order) {                      synchronized(order) {  // blocks until A's block exits
 *     state: ACCEPTED -&gt; CANCELLED               state.allowsDeliveryPartnerAssignment()
 *   }                                              // false, because state is now CANCELLED
 *                                                 return false;        // B releases rider-7 back to pool
 *                                               }
 * </pre>
 *
 * Without the shared monitor, B could read {@code state == ACCEPTED} a moment before A's
 * {@code cancel()} runs, then write {@code deliveryPartnerId} a moment after — leaving a
 * cancelled order with a reserved (and now stuck) delivery partner. Conversely, if B's
 * critical section runs first and wins, assignment succeeds and {@code cancel()} subsequently
 * still succeeds (cancellation after a partner is assigned, but before pickup, is a legitimate
 * business flow) — it is then the calling service's job (see
 * {@code OrderService#cancelOrder}) to release the now-orphaned partner back to the pool; the
 * domain-level guarantee here is only that the check-then-act around
 * {@code allowsDeliveryPartnerAssignment()} itself is race-free, not that cancellation is
 * forbidden once a partner exists. Per-order locking (rather than one global lock across all
 * orders) keeps contention scoped to a single order, which is the right granularity: unrelated
 * orders never block each other.
 */
public final class Order {

    private final String id;
    private final String customerId;
    private final String restaurantId;
    private final List<OrderItem> items;
    // Only the raw subtotal and the final payable total are retained. The Decorator pricing
    // pipeline (lld.fooddelivery.strategy.PriceCalculator) composes discounts/fees into a
    // single running Money total by design, so it does not hand back a clean "$12 off, +$20
    // fee" breakdown for free. A real invoice/line-item breakdown would need each decorator to
    // also emit a small {label, delta} record as it runs — a simple, bounded extension that is
    // intentionally omitted here to keep the pattern demonstration focused.
    private final Money subtotal;
    private final Money total;
    private final Instant placedAt;
    private final List<OrderEvent> history = Collections.synchronizedList(new ArrayList<>());
    private final List<OrderObserver> observers = new CopyOnWriteArrayList<>();

    private volatile OrderState state = PlacedState.INSTANCE;
    private volatile String deliveryPartnerId;
    private volatile String terminationReason;

    public Order(String id, String customerId, String restaurantId, List<OrderItem> items,
                 Money subtotal, Money total) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.restaurantId = Objects.requireNonNull(restaurantId, "restaurantId");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("order must contain at least one item");
        }
        this.items = List.copyOf(items);
        this.subtotal = subtotal;
        this.total = total;
        this.placedAt = Instant.now();
    }

    // ---------- identity & immutable data ----------

    public String id() {
        return id;
    }

    public String customerId() {
        return customerId;
    }

    public String restaurantId() {
        return restaurantId;
    }

    public List<OrderItem> items() {
        return items;
    }

    public Money subtotal() {
        return subtotal;
    }

    public Money total() {
        return total;
    }

    public Instant placedAt() {
        return placedAt;
    }

    public String deliveryPartnerId() {
        return deliveryPartnerId;
    }

    public String terminationReason() {
        return terminationReason;
    }

    public OrderStatus status() {
        return state.status();
    }

    public List<OrderEvent> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    // ---------- observer registration ----------

    public void addObserver(OrderObserver observer) {
        observers.add(observer);
    }

    public void removeObserver(OrderObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers(OrderEvent event) {
        for (OrderObserver observer : observers) {
            observer.onOrderUpdated(event);
        }
    }

    // ---------- lifecycle transitions (State pattern) ----------

    public synchronized void accept() {
        transition(s -> s.accept(this), "restaurant accepted order");
    }

    public synchronized void reject(String reason) {
        transition(s -> s.reject(this, reason), "restaurant rejected: " + reason);
        this.terminationReason = reason;
    }

    public synchronized void startPreparing() {
        transition(s -> s.startPreparing(this), "kitchen started preparing");
    }

    public synchronized void markReadyForPickup() {
        transition(s -> s.markReadyForPickup(this), "order ready for pickup");
    }

    public synchronized void pickUp() {
        transition(s -> s.pickUp(this), "picked up by delivery partner " + deliveryPartnerId);
    }

    public synchronized void deliver() {
        transition(s -> s.deliver(this), "delivered to customer");
    }

    public synchronized void cancel(String reason) {
        transition(s -> s.cancel(this, reason), "cancelled: " + reason);
        this.terminationReason = reason;
    }

    /**
     * Attempts to attach a delivery partner to this order. Returns {@code false} (rather than
     * throwing) when the order's current status no longer permits assignment — the caller
     * (the assignment service) is expected to treat {@code false} as "release the partner
     * reservation and give up on this order", not as an error.
     */
    public synchronized boolean assignDeliveryPartner(String partnerId) {
        if (!state.allowsDeliveryPartnerAssignment()) {
            return false;
        }
        this.deliveryPartnerId = Objects.requireNonNull(partnerId, "partnerId");
        OrderEvent event = OrderEvent.of(id, state.status(), state.status(),
                "delivery partner " + partnerId + " assigned");
        history.add(event);
        notifyObservers(event);
        return true;
    }

    private void transition(UnaryOperator<OrderState> fn, String note) {
        OrderState previous = this.state;
        OrderState next = fn.apply(previous); // throws InvalidOrderStateTransitionException on illegal transition
        this.state = next;
        OrderEvent event = OrderEvent.of(id, previous.status(), next.status(), note);
        history.add(event);
        notifyObservers(event);
    }

    @Override
    public String toString() {
        return "Order{" + id + ", status=" + state.status() + ", total=" + total + '}';
    }
}
