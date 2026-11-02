# Implementation Guide — Food Delivery System

A practical, in-the-moment guide for actually writing this code in an interview, not just
discussing it. Pairs with `INTERVIEW_PLAYBOOK.md` (which covers the *conversation*); this file
covers the *typing*.

---

## 1. Suggested implementation sequence

Write in this order — each step should compile and (ideally) be sanity-checked before moving to
the next, so you never have a long stretch of uncompilable code if time runs out:

1. **Value objects first:** `Money`, `GeoLocation`. Zero dependencies, immediately testable in
   isolation, unblock every other class's field types.
2. **Enums:** `OrderStatus`, `PartnerStatus`, `PaymentStatus`. Cheap, and naming them early
   forces you to think through the full status vocabulary before you need it.
3. **Simple entities with no behavior yet:** `MenuItem`, `Menu`, `Restaurant`, `Customer`,
   `DeliveryPartner` (reservation logic can come slightly later — get the fields/constructor
   first).
4. **Cart:** `CartItem`, `Cart` — needed before `Order` can exist, since checkout consumes a
   cart.
5. **The state machine:** `OrderState` interface first, then `PlacedState` and `AcceptedState`
   only (enough to prove the pattern compiles and reads correctly) — fill in the remaining five
   states once `Order` itself exists and compiles against the two you have.
6. **`Order` itself:** fields, constructor, the `transition(...)` dispatch helper, and the
   public lifecycle methods (`accept`, `reject`, etc.) that delegate to `state`.
7. **Remaining `OrderState` implementations:** `PreparingState`, `ReadyForPickupState`,
   `PickedUpState`, `DeliveredState`, `RejectedState`, `CancelledState`.
8. **Observer:** `OrderObserver` interface + one trivial implementation (e.g.
   `CustomerNotificationObserver`) — enough to prove `Order.notifyObservers` works.
9. **Delivery-partner reservation:** add `tryReserve()`/`release()` to `DeliveryPartner` now
   that `Order` exists and you know exactly what needs to call them.
10. **Strategy:** `DeliveryPartnerAssignmentStrategy` interface + `NearestAvailablePartnerStrategy`.
11. **Services, in dependency order:** `RestaurantService` → `CartService` →
    `DeliveryAssignmentService` → `PaymentService` → `OrderService` (the orchestrator, written
    last because it depends on everything above).
12. **Decorator pricing (if time allows):** `PricingContext`, `PriceCalculator`,
    `BaseSubtotalCalculator`, then one or two concrete decorators.
13. **A tiny `main` method / demo** wiring a restaurant, a cart, and a full order lifecycle —
    this is your live proof the whole thing actually works, and it's the single highest-value
    thing to have running with 5 minutes left on the clock.

---

## 2. Minimal compilable implementation outline

If you only have time to get something compiling and running end-to-end, this is the smallest
slice that still demonstrates every required pattern:

```java
// OrderStatus.java
public enum OrderStatus { PLACED, ACCEPTED, REJECTED, PREPARING, READY_FOR_PICKUP,
                           PICKED_UP, DELIVERED, CANCELLED }

// OrderState.java
public interface OrderState {
    default void accept(Order o)  { throw invalid(o, "accept"); }
    default void reject(Order o)  { throw invalid(o, "reject"); }
    default void cancel(Order o)  { throw invalid(o, "cancel"); }
    // ...other transitions, same shape
    default boolean allowsDeliveryPartnerAssignment() { return false; }

    private static IllegalStateException invalid(Order o, String action) {
        return new IllegalStateException(action + " not allowed from " + o.status());
    }
}

// PlacedState.java
public final class PlacedState implements OrderState {
    public static final PlacedState INSTANCE = new PlacedState();
    private PlacedState() {}
    @Override public void accept(Order o) { o.transition(OrderStatus.ACCEPTED, AcceptedState.INSTANCE); }
    @Override public void reject(Order o) { o.transition(OrderStatus.REJECTED, RejectedState.INSTANCE); }
    @Override public void cancel(Order o) { o.transition(OrderStatus.CANCELLED, CancelledState.INSTANCE); }
}

// Order.java (core fields + one transition helper)
public final class Order {
    private final String id;
    private volatile OrderStatus status = OrderStatus.PLACED;
    private volatile OrderState state = PlacedState.INSTANCE;

    public synchronized void accept() { state.accept(this); }
    public synchronized void reject() { state.reject(this); }
    public synchronized void cancel() { state.cancel(this); }

    synchronized void transition(OrderStatus newStatus, OrderState newState) {
        this.status = newStatus;
        this.state = newState;
    }

    public OrderStatus status() { return status; }
}
```

This alone — one enum, one interface, two or three state classes, and a minimal `Order` —
already demonstrates the State pattern correctly and compiles standalone. Everything else
(Strategy, Observer, Decorator, the services) layers on top without requiring changes to this
core.

---

## 3. Important methods to get exactly right

These are the methods where a subtle bug is most likely, and where interviewers tend to probe
hardest — write them carefully and narrate your reasoning as you go:

- **`Order.transition(...)` (or equivalent dispatch helper):** must be the *only* place that
  mutates `status`/`state`, and must be `synchronized`. Every public lifecycle method should
  funnel through it, never set fields directly.
- **`DeliveryPartner.tryReserve()`:** must be a single atomic CAS
  (`status.compareAndSet(AVAILABLE, BUSY)`), returning a boolean the caller checks — never a
  read-then-write across two separate statements (that reintroduces exactly the race the CAS is
  meant to prevent).
- **`Order.assignDeliveryPartner(partnerId)`:** must re-check `state.allowsDeliveryPartnerAssignment()`
  *inside* the same synchronized method that writes `deliveryPartnerId` — checking it before
  acquiring the lock (e.g. in the calling service) is a TOCTOU bug waiting to happen.
- **`DeliveryAssignmentService.assignPartnerFor(...)`:** on failing to actually assign (because
  the order transitioned out of an assignable state between CAS-reserving and calling
  `assignDeliveryPartner`), must call `partner.release()` to return the rider to the pool —
  forgetting this compensating action is the single easiest bug to introduce in this whole
  design, and the one most worth narrating out loud ("if this order is no longer assignable, I
  need to give the rider back").
- **`PaymentService.refund(...)`:** must be idempotent — calling it twice for the same order
  must not refund twice. A CAS on the payment's own status field (`SUCCESS → REFUNDED`) is the
  simplest correct approach; a plain `if (status == SUCCESS) { refund(); status = REFUNDED; }`
  is **not** safe under concurrent duplicate calls.

---

## 4. Common coding mistakes to avoid

- **Checking status, then acting, as two separate statements** outside any lock — e.g.
  `if (order.status() == PLACED) order.accept();` from calling code. This is exactly the
  check-then-act race the whole state-machine design exists to prevent; the check belongs
  *inside* the synchronized transition method (via the current `OrderState`), not outside it.
- **Using `==` or a raw `status` field without `volatile`** and expecting other threads to see
  updates — without `volatile` (or equivalent synchronization), there's no happens-before
  guarantee for unsynchronized readers.
- **Catching a broad exception type and swallowing it silently** anywhere in the assignment or
  payment path — every catch block in this domain should either rethrow, log with context, or
  trigger a compensating action (like `partner.release()`); a silent empty catch block hides
  exactly the kind of bug a staff interviewer is listening for.
- **Making `OrderState` implementations hold per-order mutable fields.** They should be
  stateless singletons; if you catch yourself adding a field to a state class, that data belongs
  on `Order` itself, not the state object.
- **Forgetting that `Cart`'s "switch restaurant" behavior needs to clear existing items** — a
  classic oversight that produces a cart with items from two restaurants, violating the
  single-restaurant-per-order invariant silently.
- **Reusing a single discount/fee decorator instance across unrelated orders with internal
  mutable accumulation state** — decorators should be stateless with respect to a specific
  order; all per-order values should flow through the `PricingContext` parameter, not be stored
  as instance fields that could leak between concurrent calculations.
- **Forgetting `compareTo`/`equals` semantics for `BigDecimal`-backed `Money`** — comparing two
  `Money` values with `.equals()` when you mean "same numeric value regardless of scale" is a
  classic subtle bug; define `Money.equals` in terms of value comparison, not raw `BigDecimal.equals`.

---

## 5. Edge cases to narrate (even if you don't fully implement all of them)

- Empty cart at checkout → reject before any `Order` is constructed.
- Item removed from menu (or marked unavailable) between add-to-cart and checkout → re-validate
  availability at checkout time, not only at add-time.
- Restaurant stops accepting orders entirely (e.g., closed for the night) mid-cart-building →
  checkout should re-check `restaurant.isAcceptingOrders()`, not just item-level availability.
- No delivery partner available at all → `assignPartnerFor` should fail gracefully (clean
  exception / retry-later signal), not throw an unhandled `NoSuchElementException` from an empty
  stream.
- Restaurant never responds to a placed order → acceptance-timeout auto-cancellation with
  refund.
- Duplicate/retried network calls to cancel or refund → must be idempotent, not merely "usually
  fine."
- Customer tries to cancel after the kitchen has started cooking → must be explicitly rejected
  by the state machine (`PreparingState` has no `cancel()` override), not silently allowed.

---

## 6. How to narrate code while writing it

- **Say the invariant before you type the code that enforces it.** "I need `accept` and
  `reject` to be mutually exclusive, so whichever one runs first should make the other one
  illegal" — *then* write the state class. This lets the interviewer evaluate your reasoning
  even if your typing is slow.
- **Name the pattern as you reach for it, not after.** "This is naturally a Strategy, because I
  want the ranking algorithm swappable" — said while writing the interface, not retroactively
  when asked "what pattern is this."
- **Talk through a trade-off out loud whenever you make a non-obvious choice**, even a small
  one: "I'm making this field `volatile` rather than wrapping every read in `synchronized`,
  because this particular read doesn't need to coordinate with a write, it just needs to not be
  stale forever."
- **If you get stuck on exact syntax** (e.g., forgetting the exact `AtomicReference.compareAndSet`
  signature), say what you want it to do in English and write a placeholder comment
  (`// CAS: set BUSY iff currently AVAILABLE, else return false`) rather than freezing — an
  interviewer cares far more that you know *what* needs to happen atomically than that you
  recall an exact method signature.

---

## 7. What can be safely omitted under time pressure

Safe to skip, cut, or stub with a one-line comment when time is short (roughly ordered from
"skip first" to "skip only if truly desperate"):

1. `RoundRobinPartnerStrategy` — mention it as "a second Strategy implementation I'd add here,"
   don't write it.
2. The full Decorator chain — one hard-coded discount calculation inline is an acceptable
   simplification if you say so explicitly.
3. `OrderTrackingObserver`'s full timeline history — a single "latest event" field is enough to
   demonstrate the Observer pattern without building the whole polling API.
4. Acceptance-timeout scheduling (`ScheduledExecutorService` wiring) — mention the concept
   ("I'd schedule an auto-cancel task here") without fully wiring a scheduler if time is critical.
5. Exception hierarchy depth — a single custom exception type with a message is fine under
   severe time pressure; the full 7-exception-class hierarchy in this repository is a "have
   time" refinement, not a baseline requirement.

**Never skip**, even under the worst time pressure: the `OrderState` interface and at least two
concrete implementations (proves the core pattern), and the CAS-based delivery-partner
reservation (`tryReserve()`) — these two are the load-bearing concurrency story of the whole
problem.

---

## 8. Simplified version implementable in 20–30 minutes

A realistic, complete-enough slice for a short round:

1. (2 min) `OrderStatus` enum, `PartnerStatus` enum.
2. (5 min) `OrderState` interface + `PlacedState`, `AcceptedState`, `PreparingState`,
   `DeliveredState`, `CancelledState` (skip `RejectedState`/`ReadyForPickupState`/`PickedUpState`
   — fold "ready for pickup" and "picked up" into the PREPARING→DELIVERED transition directly,
   explicitly stating you're compressing the lifecycle for time).
3. (5 min) Minimal `Order`: id, status, state, `synchronized` transition methods, a
   `deliveryPartnerId` field.
4. (5 min) Minimal `DeliveryPartner` with `AtomicReference<PartnerStatus>` and `tryReserve()`/
   `release()`.
5. (5 min) A tiny `AssignmentService.assign(Order, List<DeliveryPartner>)` method: nearest
   available (simple `Stream.min` by distance, no full Strategy interface — mention that in a
   longer session you'd extract this behind an interface), CAS-reserve, call
   `order.assignDeliveryPartner(...)`, release on failure.
6. (3–5 min) A tiny `main()` that places a mock order, accepts it, assigns a partner, cancels a
   *different* order to prove the illegal-transition guard works (try to cancel a
   `PREPARING` order and catch the resulting exception, printing it), and delivers the first
   one to completion.

This slice demonstrates: State pattern (core requirement), CAS-based concurrency-safe resource
reservation (the single most important correctness story in this problem), and a working,
runnable proof rather than just talked-through design — which is what a 20–30 minute round is
actually assessing.
