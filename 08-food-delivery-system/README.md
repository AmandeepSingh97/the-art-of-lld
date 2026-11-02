# Food Delivery System (Zomato/Swiggy-style) — Low-Level Design

Java 17+ reference implementation under `src/main/java/lld/fooddelivery/`, tests under
`tests/java/lld/fooddelivery/`, diagrams under `diagrams/`.

---

## 1. Problem Understanding

Design the backend domain model and in-process orchestration logic for a food-delivery
platform like Zomato/Swiggy/DoorDash: restaurants publish a menu, customers browse and add
items to a cart, place an order, the restaurant accepts or rejects it, a delivery partner is
assigned and delivers it, and payment/refund flows accompany the whole lifecycle.

**Real-world use case.** A customer opens the app, picks a restaurant, adds items to a cart,
checks out (pays), the order appears on the restaurant's tablet, the restaurant taps "Accept",
the kitchen cooks, a rider is dispatched, the rider picks up and drops off the food, and the
customer watches a live tracking screen throughout. Edge cases dominate the real engineering
effort: the restaurant doesn't respond, an item sells out mid-cart, two orders compete for the
same nearby rider, a customer cancels after a rider is already en route, a payment retry must
not double-charge.

**What the interviewer is evaluating.**
- Can you model a *multi-actor, long-lived, mutable workflow* (not just a CRUD/data-structure
  problem) with clean state-machine discipline?
- Do you reach for the State pattern instead of a scattered `if (status == ...)` spaghetti?
- Do you reach for Observer for "push updates" instead of polling-only or tight coupling?
- Do you reach for Strategy/Decorator for *genuinely variable* policies (assignment, pricing)
  without overengineering a problem that doesn't need it?
- Do you reason correctly and specifically about concurrency — not just "I'd use a lock" but
  *which* lock, at *what* granularity, and *why* a race is actually prevented?
- Do you know where to stop (this is an LLD interview, not a distributed-systems design)?

**In scope.**
- Restaurant/menu/item modelling and per-item availability toggling.
- Cart management scoped to one restaurant at a time.
- Order placement with checkout-time re-validation of availability.
- Order lifecycle state machine (State pattern) with restaurant accept/reject-with-timeout.
- Delivery-partner assignment (Strategy pattern): nearest-available by default.
- Real-time order tracking (Observer pattern).
- Payment charge and refund (including idempotency under retries).
- Pricing/discount composition (Decorator pattern).
- Concurrency correctness for the above: concurrent order placement vs. item-availability
  changes, concurrent partner-assignment races, concurrent cancellation vs. assignment.

**Out of scope** (flagged explicitly, not silently ignored — see §17 for where each would
actually be built).
- Real routing/ETA (road-network distance, traffic) — we use straight-line (Haversine)
  distance as a stand-in.
- A real payment gateway integration, fraud checks, tax/GST computation.
- Multi-restaurant single-cart/split-delivery, group ordering, scheduled/future orders.
- A persistent datastore, horizontal scaling, multi-region deployment, message queues.
- Rider earnings/incentive modelling, surge pricing, batching multiple orders per rider.

---

## 2. Requirement Gathering

### Functional — essential
1. A restaurant can publish a menu of items with name/price/category and toggle each item's
   availability independently of the others.
2. A restaurant can pause/resume accepting new orders entirely (closed for the night).
3. A customer can add/remove/update-quantity items in a cart scoped to one restaurant.
4. A customer can check out the cart into an order; checkout re-validates availability and
   restaurant acceptance state at that exact moment (not just at add-to-cart time).
5. An order moves through a well-defined lifecycle: `PLACED -> ACCEPTED -> PREPARING ->
   READY_FOR_PICKUP -> PICKED_UP -> DELIVERED`, with `REJECTED` and `CANCELLED` exits.
6. If the restaurant does not accept or reject within an SLA window, the order is
   auto-cancelled (and refunded).
7. Once a restaurant accepts an order, a delivery partner is searched for and reserved
   automatically (no manual dispatcher step) using a nearest-available policy.
8. Payment is charged at checkout; a cancelled/rejected paid order is refunded exactly once,
   even under retried/duplicate cancel-refund calls.
9. Customers (and other observers — restaurant dashboard, rider app) receive a live feed of
   order-status changes for tracking.
10. Pricing supports composable discounts (percentage-off-capped, flat-off-with-minimum,
    first-order-only) plus a distance-based delivery fee.

### Functional — optional / explicitly deferred
- Rider manually rejecting/reassigning a dispatched order.
- Partial refunds / item-level refunds.
- Scheduled orders ("order for 7pm tonight").
- Reordering from order history, favourites, ratings/reviews.
- Multiple payment methods with a shared wallet ledger.

### Non-functional
| Property | Requirement | Interview-necessary? |
|---|---|---|
| Performance | Core operations (`addItem`, `placeOrder`, state transitions) are O(1)/O(log n) in-memory; no O(n) scans over all orders on the hot path. | Yes — must be demonstrated in the code, not just claimed. |
| Scalability | Single-process design must not *structurally* block a later horizontal-scale-out (e.g. no single global lock across all orders). | Yes, as a design constraint; actual sharding/DB is production-extension (§17). |
| Availability | N/A for an in-memory single-process LLD; discussed qualitatively for production evolution. | No — production-extension. |
| Consistency | Every order's lifecycle must be linearizable from the perspective of concurrent callers (no lost updates, no illegal transitions ever observed as having succeeded). | Yes — this is the crux of the interview. |
| Extensibility | New discount types, new assignment policies, new order states must be addable without modifying unrelated code (Open/Closed). | Yes. |
| Reliability | No operation may leave shared mutable state (an order, a delivery partner, a payment) in a corrupted/inconsistent intermediate state, even under exceptions. | Yes. |
| Thread-safety | Explicit, justified, per-object — not "synchronized everywhere." | Yes, mandatory per the engineering standard for this repo. |

---

## 3. Clarifying Questions

These are the questions a strong candidate asks in the first five minutes — and the concrete
design decision each answer drives. Where I had to make an assumption to keep this repository
self-contained, I state it explicitly (these are the assumptions actually encoded in `src/`).

1. **Can a cart span multiple restaurants (for split delivery)?**
   *Why it matters:* if yes, `Cart`/`Order` become multi-restaurant aggregates with
   per-restaurant sub-totals and a more complex delivery-partner assignment (multi-pickup
   routing). *Assumption made:* **No** — one restaurant per cart, matching real Swiggy/Zomato
   UX; adding an item from a different restaurant clears the cart (`Cart.addItem`).

2. **Is payment synchronous (blocking) at checkout, or can an order exist unpaid?**
   *Why it matters:* determines whether `OrderService.placeOrder` must roll back the order on
   payment failure. *Assumption made:* payment is charged synchronously during `placeOrder`;
   a failed charge cancels the just-created order immediately rather than leaving an unpaid
   order visible to the restaurant.

3. **Once a restaurant accepts an order, can it still back out?**
   *Why it matters:* this determines whether `reject()` and delivery-assignment can overlap at
   all. *Assumption made:* **no** — `reject()` is only legal from `PLACED` (a decision made
   before commitment); a restaurant backing out after acceptance uses `cancel()`, which remains
   legal through `ACCEPTED` (see `Order`'s class javadoc for why this is the transition that
   actually races with delivery-partner assignment, not `reject()`).

4. **Can a customer cancel after the kitchen has started cooking?**
   *Why it matters:* a wrong answer here either loses restaurants money (cancels mid-cook) or
   frustrates customers (can't cancel a wrong order fast enough). *Assumption made:* self-service
   cancellation is allowed only through `ACCEPTED` (pre-`PREPARING`); after that, the domain
   throws `InvalidOrderStateTransitionException` and the real product would route the customer
   to a support/CS-mediated cancellation flow — a business rule that belongs at the state-machine
   level, not as an `if` scattered through services.

5. **Is delivery-partner assignment eager (search starts the instant the order is accepted) or
   lazy (restaurant marks "ready for pickup" first)?**
   *Why it matters:* eager assignment reduces total delivery time (rider is already en route to
   the restaurant when food is ready) but means assignment can race with early cancellation.
   *Assumption made:* **eager** — assignment starts asynchronously the moment `acceptOrder()` is
   called, which is exactly why the cancel/assign race in §11 is real and must be handled.

6. **Do discounts combine, or is only one promo applicable per order?**
   *Why it matters:* drives Strategy (one-of-many) vs. Decorator (composable pipeline) for
   pricing. *Assumption made:* discounts **compose** (percentage-off + flat-off + delivery fee
   can all apply to the same order), which is why Decorator was chosen — see §7.

7. **How is "nearest" delivery partner defined — straight-line distance, or road/ETA?**
   *Why it matters:* straight-line is O(1) to compute and good enough for an LLD demo; road/ETA
   requires an external routing service. *Assumption made:* straight-line (Haversine) distance,
   explicitly documented as a production-extension point (§17).

8. **Is there a maximum number of concurrent orders a restaurant can accept at once (kitchen
   capacity)?**
   *Why it matters:* without a cap, a restaurant can be flooded; with a cap, `acceptOrder` needs
   a counter/semaphore. *Assumption made:* **out of scope** for this repository — flagged as a
   straightforward extension (bound a `Semaphore` per restaurant) rather than implemented, to
   keep the core state-machine/concurrency story focused.

9. **Can a single payment be split/partially refunded (e.g., refund only the pizza, not the
   drink)?** *Assumption made:* **no** — one order, one payment, one refund (all-or-nothing),
   which simplifies `Payment` to a single CAS-guarded status field.

---

## 4. Domain Modelling

All types live in `src/main/java/lld/fooddelivery/...`. Responsibilities:

### Entities (have identity, are mutable over time)
| Type | Package | Responsibility |
|---|---|---|
| `Restaurant` | `domain` | Identity + location + owns one `Menu`; on/off switch for accepting orders. |
| `MenuItem` | `domain` | A sellable catalogue entry; owns its own atomic availability flag. |
| `Customer` | `domain` | Platform identity + mutable default delivery address. |
| `DeliveryPartner` | `domain` | A rider; owns its own atomic `PartnerStatus` (the reservation lock point). |
| `Order` | `domain` | **Aggregate root.** Owns its lifecycle (`OrderState`), its delivery-partner
  assignment, its observer list and event history. |
| `Payment` | `domain` | One charge attempt per order; owns its own atomic `PaymentStatus`. |

### Value objects (immutable, compared by value, no identity)
| Type | Responsibility |
|---|---|
| `Money` | BigDecimal-backed currency amount; arithmetic never produces negative totals via `subtractFloored`. |
| `GeoLocation` | Lat/long + Haversine distance. |
| `OrderItem` | **Immutable price/name snapshot** frozen onto an `Order` at checkout time — the domain invariant that a later menu price change can never retroactively alter an existing order's total. |
| `CartItem` | Mutable-quantity pre-checkout counterpart of `OrderItem`, converted via `toOrderItem()`. |
| `OrderEvent` | Immutable audit record (`record`) of one state transition, published to observers. |
| `PricingContext` | Immutable input bundle (`record`) to the pricing decorator pipeline. |

### Enums
`OrderStatus`, `PartnerStatus`, `PaymentStatus` — intentionally "dumb" data with no transition
logic baked in (see §7 for why transition logic lives in the State pattern instead).

### Interfaces / strategies / policies
| Interface | Package | Implementations |
|---|---|---|
| `OrderState` | `state` | `PlacedState`, `AcceptedState`, `PreparingState`, `ReadyForPickupState`, `PickedUpState`, `DeliveredState`, `RejectedState`, `CancelledState` — each a stateless singleton. |
| `OrderObserver` | `observer` | `CustomerNotificationObserver`, `RestaurantDashboardObserver`, `DeliveryPartnerNotificationObserver`, `OrderTrackingObserver`. |
| `DeliveryPartnerAssignmentStrategy` | `strategy` | `NearestAvailablePartnerStrategy` (default), `RoundRobinPartnerStrategy` (alternative). |
| `PriceCalculator` | `strategy` | `BaseSubtotalCalculator` + decorators `PercentageDiscountDecorator`, `FlatDiscountDecorator`, `DeliveryFeeDecorator`. |

### Domain services / repositories (application layer, `service` package)
| Service | Responsibility | Backing store |
|---|---|---|
| `RestaurantService` | Registry + mutation façade over `Restaurant`/`Menu`. | `ConcurrentHashMap<String, Restaurant>` |
| `CartService` | One `Cart` per customer, with add-time availability checks. | `ConcurrentHashMap<String, Cart>` |
| `DeliveryAssignmentService` | Pool of `DeliveryPartner`s + CAS-based reservation with compensation. | `ConcurrentHashMap<String, DeliveryPartner>` |
| `PaymentService` | One `Payment` per order id, idempotent charge/refund. | `ConcurrentHashMap<String, Payment>` |
| `OrderService` | Orchestrates the full lifecycle; the only class that knows about all the others. | `ConcurrentHashMap<String, Order>` + a `ScheduledExecutorService` for acceptance timeouts |

### Relationships, ownership, and lifecycle
- `Restaurant` **owns** (composition) exactly one `Menu`, which **owns** many `MenuItem`s. A
  `MenuItem` never outlives its `Menu`; deleting the restaurant conceptually garbage-collects
  the whole subtree (no explicit "owning restaurant" back-pointer is needed).
- `Cart` **owns** (composition) its `CartItem`s, scoped to one customer at a time; a `Cart` is
  transient — it is cleared and effectively "reborn" on every checkout.
- `Order` **owns** (composition) an immutable list of `OrderItem`s (deep-copied snapshots, not
  references into `Cart`/`Menu`) and **associates with** (not owns) a `customerId`,
  `restaurantId`, and `deliveryPartnerId` by reference (string id), not by object reference —
  this deliberately decouples `Order`'s lifetime from the lifetime of the `Customer`/
  `Restaurant`/`DeliveryPartner` objects, which is the right call once those live behind their
  own services/repositories.
- `Order` **associates with** (not owns) its `OrderState` — states are flyweight singletons
  shared across every order currently in that status; no per-order state allocation.
- `Order` **associates with** zero-or-more `OrderObserver`s, added/removed independently of the
  order's own lifecycle (an observer's lifetime is managed by whoever subscribed it).

---

## 5. Domain Invariants

Business rules that must **always** hold, and exactly where each is enforced in code:

1. **An order's status only ever moves along a legal edge of the state graph** (see
   `diagrams/order-state-diagram.md`). *Enforced by:* every `OrderState` subclass only
   overriding the transitions it legally supports; `OrderState`'s default methods throw
   `InvalidOrderStateTransitionException` for everything else — a fail-closed design (`state/OrderState.java`).
2. **A delivery partner is only ever assigned to an order that is `ACCEPTED` or `PREPARING`.**
   *Enforced by:* `OrderState.allowsDeliveryPartnerAssignment()`, consulted inside
   `Order.assignDeliveryPartner`, both under the order's own monitor (`domain/Order.java`).
3. **A single delivery partner can never be reserved by two orders at once.**
   *Enforced by:* `DeliveryPartner.tryReserve()` — a single CAS
   (`AVAILABLE -> BUSY`); losing the CAS means the caller must try another candidate
   (`domain/DeliveryPartner.java`, `service/DeliveryAssignmentService.java`).
4. **A payment is refunded at most once.** *Enforced by:* `Payment.markRefunded()` — a CAS from
   `SUCCESS` to `REFUNDED`; a second concurrent/duplicate call fails harmlessly
   (`domain/Payment.java`).
5. **An order's price, once placed, never changes even if the restaurant later edits menu
   prices.** *Enforced by:* `OrderItem` is an immutable snapshot of price/name captured at
   checkout time, never a live reference to `MenuItem` (`domain/OrderItem.java`,
   `domain/CartItem.toOrderItem()`).
6. **An item added to a cart may still be rejected at checkout if it went out of stock in the
   meantime.** *Enforced by:* `OrderService.placeOrder` re-validates every cart item's current
   availability against the live `Menu`, not against a cached snapshot from add-to-cart time
   (`service/OrderService.java`).
7. **Money values are never negative as a result of discount arithmetic.** *Enforced by:*
   `Money.subtractFloored`, used by every discount decorator instead of plain `subtract`
   (`domain/Money.java`, `strategy/*Decorator.java`).
8. **A cart belongs to exactly one restaurant at a time.** *Enforced by:* `Cart.addItem`
   clearing existing items when a different restaurant's item is added
   (`domain/Cart.java`).

---

## 6. Architecture and Class Diagram

- Component diagram: [`diagrams/component-diagram.md`](diagrams/component-diagram.md)
- Class diagram: [`diagrams/class-diagram.md`](diagrams/class-diagram.md)
- Order lifecycle state diagram: [`diagrams/order-state-diagram.md`](diagrams/order-state-diagram.md)
- Sequence diagrams (checkout, acceptance+assignment, the cancel/assign race, refund
  idempotency): [`diagrams/sequence-diagrams.md`](diagrams/sequence-diagrams.md)

These diagrams are drawn directly from, and are kept in lockstep with, the actual class and
method names in `src/main/java/lld/fooddelivery/` — every class/method named in a diagram
exists verbatim in the code.

---

## 7. Design Patterns

| Pattern | Where applied | Why needed | Alternative considered | Why rejected |
|---|---|---|---|---|
| **State** | `Order` lifecycle (`state` package) | The order lifecycle has 8 statuses with asymmetric legal transitions (e.g. `cancel` legal from `PLACED`/`ACCEPTED` but not `PREPARING`). A `switch (status)` block duplicated across every service method that mutates an order grows unboundedly and is easy to get subtly wrong as statuses are added. | A single `switch`/`if-else` chain inside `Order` itself. | Works for 3–4 statuses; at 8 statuses × 7 possible actions it becomes a 56-cell matrix repeated at every call site, with no compiler help catching a missing case. State pattern makes "what can happen while PREPARING" a single, independently reviewable class. |
| **Observer** | Real-time order tracking (`observer` package) | Multiple independent consumers (customer app, restaurant dashboard, rider app, a tracking-history buffer) need to react to the same order events without `Order` knowing about any of them by name. | A polling API only (`GET /orders/{id}` on an interval). | Polling alone works but wastes requests and adds latency to "my order just got accepted" notifications; Observer supports push *and* (via `OrderTrackingObserver`) still supports polling for free, so it strictly dominates for a negligible cost. |
| **Strategy** | Delivery-partner selection (`strategy.DeliveryPartnerAssignmentStrategy`) | "Which partner to offer first" is a genuinely swappable policy — nearest-available today, a fairness/round-robin policy for off-peak hours tomorrow, a ML-ranked ETA model later — without touching the CAS-reservation/compensation logic around it. | Hard-code the nearest-distance comparator inline in `DeliveryAssignmentService`. | Works until a second real policy is needed (as demonstrated by `RoundRobinPartnerStrategy` existing in this very repo) — then it must be ripped out into its own abstraction anyway; doing it up front costs one interface and pays for itself immediately. |
| **Decorator** | Pricing/discounts (`strategy.PriceCalculator` + decorators) | Discounts and fees *compose*: "20% off up to ₹100" **and** "₹50 off first order" **and** a distance-based delivery fee can all apply to one order simultaneously. | A single `PricingStrategy` enum/interface with one method per combination. | A flat Strategy needs a combinatorial-explosion of classes (one per *combination* of applicable rules) to express stacking; Decorator lets the caller compose an arbitrary pipeline at runtime by nesting constructors — see `demo/FoodDeliveryDemo.java` for a 3-decorator stack built in one expression. |
| **Facade / Orchestrator** (not a GoF-named pattern, but a deliberate design choice) | `OrderService` | One class needs to coordinate cart validation, pricing, payment, the state machine, and delivery assignment into one checkout operation; spreading this across the caller (e.g. a REST controller) would duplicate the orchestration logic at every call site. | Push the orchestration logic into the API/controller layer directly. | Keeping it in a dedicated service makes the orchestration unit-testable without an HTTP layer (see `OrderServiceIntegrationTest`) and reusable from multiple entry points (REST, a message-queue consumer, a CLI demo). |

**Patterns deliberately *not* used** (to preempt the "why didn't you use X" follow-up):
- **Singleton** for the services — none of `RestaurantService`/`CartService`/etc. are
  Singletons; they are plain objects wired up once at application start (see
  `demo/FoodDeliveryDemo.main`). A real app would use dependency injection (Spring, Guice); a
  hand-rolled Singleton would only add global mutable state and make testing harder (you could
  never construct two independent test instances).
- **Factory** for `Order`/`Payment` construction — their constructors are simple enough
  (a handful of required fields, no polymorphic creation logic) that a Factory would be pure
  ceremony. If order creation later needed to branch on order *type* (dine-in vs. delivery vs.
  pickup, say), a Factory would earn its keep then.
- **Command** for the lifecycle actions (`accept`/`reject`/...) — these are simple method calls
  with no need for undo/redo, queuing, or logging-as-a-generic-command-object; adding Command
  here would be indirection without a payoff.

---

## 8. API and Method Design

Public surface (all in `lld.fooddelivery.service` unless noted). Exceptions are custom,
unchecked (`RuntimeException` subclasses in `lld.fooddelivery.exception`), matching the
convention that domain-rule violations are programmer-visible failures, not recoverable
checked conditions the caller is forced to handle inline.

```java
// RestaurantService
void registerRestaurant(Restaurant restaurant);
Optional<Restaurant> findRestaurant(String restaurantId);
Restaurant requireRestaurant(String restaurantId);            // throws IllegalArgumentException
void setItemAvailability(String restaurantId, String itemId, boolean available);
void setAcceptingOrders(String restaurantId, boolean accepting);

// CartService
Cart cartFor(String customerId);
void addItem(String customerId, String restaurantId, String menuItemId, int quantity);
                                                                // throws RestaurantNotAcceptingOrdersException,
                                                                //        ItemUnavailableException
void removeItem(String customerId, String menuItemId);
void clear(String customerId);

// OrderService
Order placeOrder(String customerId, PriceCalculator pricingPipeline,
                  boolean firstOrderForCustomer, double deliveryDistanceKm);
                                                                // throws EmptyCartException, ItemUnavailableException,
                                                                //        RestaurantNotAcceptingOrdersException,
                                                                //        PaymentFailedException
Order findOrder(String orderId);                               // throws OrderNotFoundException
void acceptOrder(String orderId);                              // throws InvalidOrderStateTransitionException
void rejectOrder(String orderId, String reason);                // throws InvalidOrderStateTransitionException
void startPreparing(String orderId);
void markReadyForPickup(String orderId);
void pickUp(String orderId);
void deliver(String orderId);
void cancelOrder(String orderId, String reason);                // throws InvalidOrderStateTransitionException

// DeliveryAssignmentService
void registerPartner(DeliveryPartner partner);
Optional<DeliveryPartner> assignPartnerFor(Order order, GeoLocation pickupLocation);
void release(String partnerId);

// PaymentService
Payment charge(Order order);                                   // throws PaymentFailedException
boolean refund(String orderId);                                 // idempotent, returns false if nothing to refund
```

**Request/response objects.** This repository favours small, purpose-built value objects over
generic DTOs: `PricingContext` (input to pricing), `OrderEvent` (output of every transition).
There is deliberately no single giant `OrderRequest`/`OrderResponse` pair — each method takes
exactly the parameters it needs, which keeps call sites self-documenting (see §16 for the
trade-off against a REST-controller-friendly single-DTO style).

**Validation responsibilities.** Each service validates only what it owns:
`CartService`/`OrderService` validate availability/acceptance against live `Restaurant`/`Menu`
state; `Order` validates lifecycle legality against its own `OrderState`; `Payment` validates
refund legality against its own status. No service reaches into another's internals to
validate on its behalf (e.g. `OrderService` does not read `MenuItem.available` directly — it
asks `Restaurant.menu().isItemAvailable(...)`).

---

## 9. Core Implementation

Full source lives in `src/main/java/lld/fooddelivery/`. The pieces most worth reading end to
end for an interview:

- **State pattern:** `state/OrderState.java` (the fail-closed interface) and
  `state/AcceptedState.java` (a representative concrete state).
- **Aggregate root with per-order locking:** `domain/Order.java`.
- **CAS-based reservation:** `domain/DeliveryPartner.java` and
  `service/DeliveryAssignmentService.java`.
- **Decorator pricing pipeline:** `strategy/PriceCalculator.java`,
  `strategy/PercentageDiscountDecorator.java`.
- **Orchestration with acceptance timeout:** `service/OrderService.java`.
- **Runnable demo:** `src/main/java/lld/fooddelivery/demo/FoodDeliveryDemo.java` — builds the
  whole service graph, places an order, accepts it, watches assignment happen asynchronously,
  walks it to `DELIVERED`, then runs a cancel+refund scenario. Run with:
  ```
  javac -d out $(find src/main -name '*.java')
  java -cp out lld.fooddelivery.demo.FoodDeliveryDemo
  ```

Representative excerpt — the compare-and-set delivery-partner reservation with compensation
(the heart of the concurrency story), from `service/DeliveryAssignmentService.java`:

```java
public Optional<DeliveryPartner> assignPartnerFor(Order order, GeoLocation pickupLocation) {
    List<DeliveryPartner> tried = new ArrayList<>();
    while (true) {
        List<DeliveryPartner> candidates = new ArrayList<>(partners.values());
        candidates.removeAll(tried);
        Optional<DeliveryPartner> candidateOpt = strategy.selectPartner(pickupLocation, candidates);
        if (candidateOpt.isEmpty()) {
            return Optional.empty();
        }
        DeliveryPartner candidate = candidateOpt.get();
        if (!candidate.tryReserve()) {
            tried.add(candidate);     // lost the CAS race to another order; try the next-best
            continue;
        }
        if (order.assignDeliveryPartner(candidate.id())) {
            return Optional.of(candidate);
        }
        candidate.release();          // order no longer accepts assignment (e.g. cancelled); compensate
        return Optional.empty();
    }
}
```

---

## 10. Data Structures and Algorithms

| Component | Structure used | Why | Alternative | Complexity |
|---|---|---|---|---|
| `Menu` item lookup | `ConcurrentHashMap<String, MenuItem>` | O(1) lookup/update by id; lock-free reads concurrent with writes. | `TreeMap` (sorted by id) | O(1) avg vs. O(log n); no ordering requirement exists, so the hash map strictly wins. |
| Restaurant/order/payment registries | `ConcurrentHashMap<String, T>` | Same O(1) amortised lookup; safe publication without external locking. | External `synchronized` + `HashMap` | Equivalent correctness, strictly worse — `ConcurrentHashMap` uses striped locking internally and never blocks readers against readers. |
| Partner selection (nearest) | Linear scan + `Comparator.comparingDouble` (`Stream.min`) | O(n) over the *current candidate pool*; simplest-correct approach for the problem's realistic scale (a few dozen riders near a given restaurant at once). | A spatial index (geohash bucket / quad-tree / k-d tree) for O(log n) nearest-neighbour. | O(n) vs O(log n) — see §14 for exactly when the index becomes worth the complexity. |
| Order event history | `Collections.synchronizedList(ArrayList)` for the authoritative per-order log; `CopyOnWriteArrayList` for `OrderTrackingObserver`'s read-heavy view | Append-heavy (one event per transition), read-heavy from the tracking screen; `CopyOnWriteArrayList` is purpose-built for "writes rare, reads frequent, iterate-while-mutating must never throw." | `ArrayList` + manual lock everywhere it's read | Would require every external reader to also synchronize correctly — easy to get wrong; copy-on-write pushes that correctness into the collection itself. |
| Order's observer list | `CopyOnWriteArrayList<OrderObserver>` | Observers are added rarely (subscribe once) and iterated frequently (every transition); never want a `ConcurrentModificationException` if a transition fires while a late subscriber attaches. | Plain `ArrayList` + external lock around notify | Same correctness, but at the cost of holding the order's monitor across every observer callback — a slow/misbehaving observer would then block all state transitions on that order. |
| `Order.items` | `List.copyOf(...)` (immutable) | Defensive copy at construction time; an `Order`'s items can never be mutated after creation by a caller holding a stale reference to the original list. | Store the caller's list directly | Storing the caller's list directly would let an external mutation corrupt a "frozen" order — violates Domain Invariant #5. |
| Delivery-partner reservation | `AtomicReference<PartnerStatus>` + single CAS | Lock-free, O(1); exactly expresses "reserve iff currently available" as one atomic operation. | `synchronized` method + plain field | Equivalent correctness at this granularity; CAS is preferred here because the caller needs to *try* several partners in a retry loop, and taking/releasing a lock per failed attempt is strictly more overhead for no extra safety. |

---

## 11. Concurrency and Thread Safety

This section is the crux of this problem; it is deliberately detailed because every claim
below is backed by a test in `tests/java/lld/fooddelivery/`.

### 11.1 Shared mutable state inventory

| Shared state | Who writes it | Who reads it concurrently | Guard |
|---|---|---|---|
| `MenuItem.available` | Restaurant (toggles stock) | `CartService.addItem`, `OrderService.placeOrder` (checkout re-validation) | `AtomicBoolean` |
| `Order.state` + `Order.deliveryPartnerId` | Restaurant (accept/reject/cancel), kitchen (startPreparing/markReady), rider (pickUp/deliver), assignment service, acceptance-timeout task | Any of the above, concurrently | `synchronized` on the `Order` instance — **one lock covers both fields because they are causally linked** (see §11.2). |
| `DeliveryPartner.status` | `DeliveryAssignmentService` (reserve/release) | Every concurrent assignment attempt across all orders | `AtomicReference<PartnerStatus>` CAS |
| `Payment.status` | `PaymentService.charge`/`refund` | Concurrent/duplicate refund calls (retries) | `AtomicReference<PaymentStatus>` CAS |
| `Cart` contents | The owning customer (possibly from multiple devices/tabs) | Same customer only — no cross-customer contention | `synchronized` methods on `Cart` |
| `orders` / `restaurants` / `partners` / `paymentsByOrderId` registries | Any service, at registration/creation time | Every lookup | `ConcurrentHashMap` |

### 11.2 The two races this design is required to prevent (and how)

**Race 1 — two orders competing for the same nearest delivery partner.**
`DeliveryPartnerAssignmentStrategy.selectPartner` only *ranks* candidates; it never mutates
anything. The actual reservation is `DeliveryPartner.tryReserve()`, a single
`compareAndSet(AVAILABLE, BUSY)`. If two assignment threads both rank the same partner as
"best," only one wins the CAS; the other's `tryReserve()` returns `false`, and
`DeliveryAssignmentService.assignPartnerFor` retries with the next-best candidate rather than
believing it succeeded. See `OrderConcurrencyTest.onlyOneOfTwoConcurrentOrdersCanReserveTheSameSolePartner`.

**Race 2 — a delivery partner is reserved for an order that is concurrently cancelled.** This
is the scenario called out in the assignment brief ("restaurant rejects while a delivery
partner is being assigned"); the closest *legally reachable* instance of it in this model is
cancellation, not rejection — rejection is only legal pre-acceptance, and assignment only ever
starts post-acceptance, so the two can never actually overlap in this state machine (see §3,
clarifying question 3, and the class javadoc on `Order`). What **can** genuinely overlap is:

```
Thread A (cancelOrder)                      Thread B (assignPartnerFor)
------------------------                    ---------------------------
order.cancel("changed mind")                 partner.tryReserve()        // wins CAS, partner now BUSY
  synchronized(order) {                       order.assignDeliveryPartner(partnerId)
    state: ACCEPTED -> CANCELLED                synchronized(order) {    // blocks until A's block exits
  }                                               state.allowsDeliveryPartnerAssignment()
                                                    // false — state is CANCELLED
                                                  return false;
                                                }
                                              partner.release()           // compensate: never left stranded as BUSY
```

Both `Order.cancel(...)` and `Order.assignDeliveryPartner(...)` are `synchronized` on the
**same** intrinsic lock (the `Order` instance itself), which makes "check current state, then
decide whether to mutate" one atomic, serialized critical section from the perspective of both
threads. Whichever thread's `synchronized` block runs first **fully** determines the outcome
for the other:
- If cancellation runs first, assignment's `allowsDeliveryPartnerAssignment()` check correctly
  observes `CANCELLED` and refuses, and the caller releases the partner — no partner is ever
  left reserved against a dead order.
- If assignment runs first, it succeeds (legitimately — the order was still `ACCEPTED` at that
  instant), and the **subsequent** `cancel()` still succeeds too (cancelling an order that
  already has an assigned-but-not-yet-picked-up rider is a legitimate business flow); it is then
  `OrderService.cancelOrder`'s job to release that now-orphaned partner back to the pool, which
  it does (`deliveryAssignmentService.release(order.deliveryPartnerId())`) — see
  `OrderConcurrencyTest.partnerAssignmentRacingWithCancellationNeverLeavesAStrandedReservation`
  and `OrderServiceIntegrationTest.cancellingAfterPartnerAssignmentReleasesThePartnerBackToThePool`.

**Why one lock, not two (one for status, one for delivery-partner-id)?** Because the *decision*
of whether assignment is allowed is a function of status, so checking status and writing the
partner id must be atomic with respect to anything that changes status. Two separate locks
would re-introduce the exact TOCTOU (time-of-check-to-time-of-use) gap the design exists to
close. **Why per-order, not one global lock across all orders?** Contention would be
needless — cancelling order #42 has nothing to do with assigning a partner to order #99;
per-order locking keeps the critical section (and the contention) scoped to exactly the
resource it protects.

### 11.3 Illegal transitions under concurrency

Two threads racing to `accept()` and `reject()` the very same `PLACED` order: both methods are
`synchronized` on the order, so they execute strictly serialized — whichever runs first wins
cleanly (status becomes `ACCEPTED` or `REJECTED`), and the other observes
`InvalidOrderStateTransitionException` from the now-stale precondition baked into the losing
state object's method (e.g. `RejectedState` has no `accept()` override, so calling `accept()`
after `reject()` already ran throws). Critically, **the failed call never partially mutates
anything** — `Order.transition(...)` computes `next = fn.apply(previous)` (which throws before
any field is written) before assigning `this.state = next`, so a thrown
`InvalidOrderStateTransitionException` leaves `Order` byte-for-byte as it was.
See `OrderConcurrencyTest.concurrentAcceptAndRejectResolvesToExactlyOneWinner` (run 50× via
`@RepeatedTest` to surface interleaving-dependent races) and
`OrderStateMachineTest.cancelAfterPreparingStartedIsRejected` (verifies the failed-transition
no-partial-mutation guarantee deterministically, single-threaded).

### 11.4 Visibility (not just atomicity)

Every field read cross-thread without a lock is either `volatile` (`Order.state`,
`Order.deliveryPartnerId`, `Order.terminationReason`, `Cart.restaurantId` is guarded by
`synchronized` instead since it's read-modify-write) or backed by `java.util.concurrent.atomic`
(`MenuItem.available`, `DeliveryPartner.status`, `Payment.status`, `CartItem.quantity`). This
is not a stylistic choice — a plain `boolean`/reference field written by one thread and read by
another without `volatile`/atomic/lock has **no guarantee** the reader ever observes the write
at all (not just "might see a stale value briefly" — the JMM permits never observing it),
which is why every one of these fields is explicitly one of the three.

### 11.5 Deadlock analysis

No code path acquires two different `Order` monitors at once, nor an `Order` monitor and a
`Cart`/`DeliveryPartner` monitor while holding the other (all cross-object coordination in this
codebase is expressed via lock-free CAS on the *other* object plus a single
`synchronized(order)` section on *this* object — see `Order.assignDeliveryPartner`, which never
calls into `DeliveryPartner` while holding `this`'s monitor; `DeliveryAssignmentService` calls
`candidate.tryReserve()` *before* calling `order.assignDeliveryPartner(...)`, so there is a
strict lock-ordering: CAS first, order-monitor second, uniformly, everywhere). With a single
total order of lock acquisition (and most "locks" being lock-free CAS, not locks at all),
circular wait — the necessary condition for deadlock — cannot arise.

### 11.6 Optimistic vs. pessimistic locking — justification per component

- **Pessimistic (`synchronized`)** on `Order`: transitions are rare relative to reads
  (an order transitions ~7 times total over its lifetime) and each transition does real,
  multi-step work (check legality, mutate, append history, notify observers) that must be
  atomic as a unit — a CAS loop re-trying a multi-field, multi-step update would be far more
  complex than a short `synchronized` block for a low-contention resource (contention is scoped
  to one order, touched by at most a handful of concurrent actors).
- **Optimistic (CAS)** on `DeliveryPartner.status` and `Payment.status`: each is a *single*
  field with a *single* legal forward transition being contended for identical reasons
  (try-and-retry is cheap and correct); no multi-field invariant needs protecting at that
  granularity.

---

## 12. Persistence and Database Design

This repository is **intentionally in-memory** (`ConcurrentHashMap`-backed registries) because
the LLD interview is scoped to the domain model and concurrency correctness, not to schema
design or SQL. Were this backed by a real database, the natural relational schema would be:

```sql
CREATE TABLE restaurant (
    id VARCHAR PRIMARY KEY, name VARCHAR, lat DOUBLE, lng DOUBLE, accepting_orders BOOLEAN
);
CREATE TABLE menu_item (
    id VARCHAR PRIMARY KEY, restaurant_id VARCHAR REFERENCES restaurant(id),
    name VARCHAR, price_minor_units BIGINT, category VARCHAR, available BOOLEAN,
    version BIGINT  -- optimistic-lock column for availability toggles under write contention
);
CREATE INDEX idx_menu_item_restaurant ON menu_item(restaurant_id);

CREATE TABLE delivery_partner (
    id VARCHAR PRIMARY KEY, name VARCHAR, lat DOUBLE, lng DOUBLE,
    status VARCHAR CHECK (status IN ('AVAILABLE','BUSY','OFFLINE')), version BIGINT
);
CREATE INDEX idx_partner_status_location ON delivery_partner(status, lat, lng); -- supports a bounding-box pre-filter

CREATE TABLE orders (
    id VARCHAR PRIMARY KEY, customer_id VARCHAR, restaurant_id VARCHAR REFERENCES restaurant(id),
    status VARCHAR, delivery_partner_id VARCHAR NULL REFERENCES delivery_partner(id),
    subtotal_minor_units BIGINT, total_minor_units BIGINT,
    placed_at TIMESTAMP, version BIGINT  -- optimistic lock for state transitions
);
CREATE INDEX idx_orders_status ON orders(status);           -- for the acceptance-timeout sweep job
CREATE INDEX idx_orders_restaurant_status ON orders(restaurant_id, status); -- restaurant's live dashboard

CREATE TABLE order_item (
    order_id VARCHAR REFERENCES orders(id), menu_item_id VARCHAR, name VARCHAR,
    unit_price_minor_units BIGINT, quantity INT,
    PRIMARY KEY (order_id, menu_item_id)
);

CREATE TABLE order_event (
    id BIGINT PRIMARY KEY, order_id VARCHAR REFERENCES orders(id),
    from_status VARCHAR, to_status VARCHAR, note VARCHAR, occurred_at TIMESTAMP
);
CREATE INDEX idx_order_event_order ON order_event(order_id, occurred_at);

CREATE TABLE payment (
    id VARCHAR PRIMARY KEY, order_id VARCHAR UNIQUE REFERENCES orders(id),
    amount_minor_units BIGINT, status VARCHAR, version BIGINT
);
```

**Transactions/isolation, mapped onto this schema:**
- `Order.accept()`/`reject()`/`cancel()` -> `UPDATE orders SET status=?, version=version+1 WHERE
  id=? AND version=? AND status IN (<legal predecessors>)` at `READ COMMITTED` with an
  **optimistic `version` check** standing in for the in-memory `synchronized(order)` block — a
  0-row update means "someone else changed it first," and the service retries/aborts exactly
  like a failed CAS.
- `DeliveryPartner.tryReserve()` -> `UPDATE delivery_partner SET status='BUSY', version=version+1
  WHERE id=? AND status='AVAILABLE' AND version=?` — the SQL-level equivalent of the in-memory
  CAS, same semantics.
- `Payment.markRefunded()` -> `UPDATE payment SET status='REFUNDED' WHERE order_id=? AND
  status='SUCCESS'` — a conditional update is itself the idempotency guard; a duplicate call
  affects 0 rows the second time.

**Why no DB is actually necessary for this exercise:** every invariant above is expressible,
and *is* expressed, as an in-memory atomic/CAS/lock operation with identical semantics to the
SQL version; the DB only becomes necessary for durability across process restarts and
multi-process/horizontal scaling — both explicitly out of scope for a single-process LLD
(see §17 for exactly how this evolves).

---

## 13. Failure Scenarios

| Scenario | Handling |
|---|---|
| **Restaurant never responds to a placed order.** | Acceptance-timeout task (`OrderService.scheduleAcceptanceTimeout`) auto-cancels and refunds after the configured SLA; the scheduled task is itself cancelled the instant the restaurant does respond (`cancelAcceptanceTimeout`), so there's no race where a late-but-genuine acceptance is clobbered by a timeout firing moments later — see `OrderServiceIntegrationTest.acceptingAfterTimeoutTaskWasScheduledCancelsTheTimeoutSoNoLateAutoCancelOccurs`. |
| **Payment charge fails at checkout.** | `OrderService.placeOrder` catches `PaymentFailedException`, immediately `cancel()`s the just-created order (so it never becomes visible to the restaurant in a "paid but not really" state), and re-throws to the caller. |
| **Duplicate/retried refund request** (e.g. an at-least-once message queue redelivering a "cancel order" event). | `Payment.markRefunded()`'s CAS makes the second call a no-op (`false`) rather than double-crediting — see `PaymentServiceTest.refundIsIdempotentUnderConcurrentDuplicateCalls`. |
| **Duplicate/retried charge request** for the same order id. | `PaymentService.charge` uses `putIfAbsent` keyed by order id; a retried call returns the *existing* `Payment` rather than creating a second charge — see `PaymentServiceTest.chargeIsIdempotentForTheSameOrderId`. |
| **Item goes out of stock between "add to cart" and "place order."** | Checkout re-validates every cart item's live availability; an unavailable item throws `ItemUnavailableException` and the order is never created — see `OrderServiceIntegrationTest.checkoutRevalidatesItemAvailabilityEvenIfAddedWhileAvailable`. |
| **No delivery partner is available anywhere.** | `DeliveryAssignmentService.assignPartnerFor` returns `Optional.empty()` rather than throwing; `OrderService.acceptOrder` runs this on a background executor, so a temporarily partner-less order simply stays `ACCEPTED` and would, in production, be retried on a backoff schedule or escalated to ops (explicitly flagged as a production-extension in §17 — this repo demonstrates the *assignment* correctness, not an infinite-retry scheduler). |
| **Delivery partner goes offline mid-reservation-attempt.** | `tryReserve()`'s CAS only succeeds from `AVAILABLE`; a partner that went `OFFLINE` a moment before simply fails the CAS like any other losing race, and the next-best candidate is tried. |
| **Customer tries to cancel after the kitchen has started cooking.** | `PreparingState` does not override `cancel()`, so the default `OrderState.cancel()` throws `InvalidOrderStateTransitionException`; `OrderService.cancelOrder` does not swallow this — it propagates to the caller, who is expected to route to a support flow. |
| **Resource exhaustion — unbounded order/payment maps growing forever.** | Explicitly out of scope for an in-memory demo; production evolution (§17) covers eviction/archival to a real datastore. |
| **Exception thrown mid-transition leaves state corrupted.** | `Order.transition(...)` computes the *next* state before mutating `this.state` — if `fn.apply(previous)` throws, no field has been written yet, so the order is provably unchanged (see §11.3). |

---

## 14. Performance and Scalability

**Bottlenecks and fixes, single-process:**
- `NearestAvailablePartnerStrategy` is O(n) over the current candidate pool per assignment
  attempt. For the realistic scale of this problem (tens of riders active near any one
  restaurant at a time), O(n) with a small constant (`Stream.min` over a `List`) comfortably
  outperforms the bookkeeping overhead of a spatial index. **Fix if this becomes a bottleneck:**
  bucket riders by geohash cell (or a quad-tree keyed on lat/long) so a search only scans the
  3×3 neighbourhood of cells around the pickup point — turns O(n) into O(k) where k is riders
  per cell, independent of total rider count city-wide.
- `Order.history`/`OrderTrackingObserver.events` grow unboundedly for a long-lived order. In
  practice an order has ~7 events total (bounded by the state machine's own diameter), so this
  is a non-issue at this scale; it would only matter if events were emitted per-GPS-ping rather
  than per-status-transition (out of scope here).
- Acceptance-timeout tasks use one `ScheduledFuture` per order on a shared
  `ScheduledExecutorService` — fine up to tens of thousands of concurrently in-flight
  `PLACED` orders; beyond that, a single timer wheel (e.g. Netty's `HashedWheelTimer`) amortizes
  per-timer bookkeeping far better than one `ScheduledFuture` object per order.

**Multi-threaded (still single process) optimizations already present:**
- Lock-free CAS for the two highest-contention single-field resources
  (`DeliveryPartner.status`, `Payment.status`) instead of locks, minimizing context-switch
  overhead under contention.
- `ConcurrentHashMap` everywhere a registry is read far more often than written, so reads never
  block on writer activity to *other* keys (and in practice rarely block on the same key either,
  thanks to internal striping).
- Per-order locking (not a single global order-table lock) keeps the *contention domain* for
  any one order's transitions limited to the handful of actors touching that specific order.

**Distributed-system extensions (explicitly *not* implemented here — this is an LLD problem,
not a systems-design one):**
- Partition orders/restaurants by city/region across multiple service instances; replace
  `ConcurrentHashMap` with a sharded key-value store (e.g. Redis/DynamoDB) keyed by order id,
  with the optimistic-version-column pattern from §12 replacing in-process CAS.
- Replace the in-process `ScheduledExecutorService` acceptance-timeout with a durable,
  crash-safe mechanism (a delayed message in a queue, or a cron sweep over `orders` rows where
  `status='PLACED' AND placed_at < now() - sla`), since an in-memory timer is lost on a process
  restart.
- Replace direct method-call Observer notification with an event bus (Kafka/SNS) so
  customer-app push, restaurant-dashboard, and rider-app notifications can scale and fail
  independently of the order-processing hot path.
- Do **not** prematurely distribute delivery-partner assignment across regions in a way that
  requires cross-region coordination for a single rider — assignment is inherently a
  local/regional operation (a rider in Bangalore is never a candidate for an order in Mumbai),
  so this is a natural, low-risk sharding boundary, not a distributed-transaction problem.

---

## 15. Testing Strategy

All tests live in `tests/java/lld/fooddelivery/` and run against JUnit 5
(`junit-platform-console-standalone`). Categories, with representative files:

| Category | File | What it proves |
|---|---|---|
| **Unit — state machine** | `OrderStateMachineTest` | Every legal transition succeeds; every illegal one throws `InvalidOrderStateTransitionException` with the *correct* `currentStatus`/`attemptedAction`; a failed transition never partially mutates the order. |
| **Unit — pricing decorators** | `PriceCalculatorDecoratorTest` | Percentage-discount capping, flat-discount eligibility gating (min order value, first-order-only), delivery-fee distance scaling, 3-decorator composition order, and the "never negative" floor. |
| **Unit — assignment strategies** | `DeliveryAssignmentStrategyTest` | Nearest-available correctly ignores `BUSY`/`OFFLINE` partners; round-robin actually cycles rather than always returning the same candidate. |
| **Boundary / negative** | `CartServiceTest.addingAnUnavailableItemIsRejectedImmediately`, `PaymentServiceTest.chargeRejectsAZeroAmountOrder`, `OrderServiceIntegrationTest.placingAnOrderWithAnEmptyCartFails` | Zero/negative/empty inputs are rejected with the correct, specific exception type — not a generic `RuntimeException` or (worse) silent success. |
| **Concurrency** | `OrderConcurrencyTest` (all three tests), `CartServiceTest.concurrentAvailabilityToggleNeverCorruptsReads`, `PaymentServiceTest.refundIsIdempotentUnderConcurrentDuplicateCalls` | The exact races described in §11, driven with `CountDownLatch`-gated threads to force simultaneous starts, repeated 50× (`@RepeatedTest`) where the interleaving is non-deterministic, asserting an *invariant* (exactly one winner; never a stranded `BUSY` partner) rather than a specific thread-interleaving outcome. |
| **Failure-injection** | `OrderServiceIntegrationTest.orderIsAutoCancelledAndRefundedIfRestaurantNeverResponds`, `.cancellingAfterPreparingStartedIsRejectedByTheOrderService` | Simulated restaurant silence and simulated "too-late" cancellation attempts produce the documented failure behaviour, not a hang or a silent no-op. |
| **Integration** | `OrderServiceIntegrationTest` (remaining tests) | The full service graph wired together exactly as `FoodDeliveryDemo` wires it, exercising cart -> checkout -> payment -> accept -> async assignment -> lifecycle -> delivery/cancel, asserting on real object state, not mocks. |

Representative concurrency test (abbreviated; full version in
`tests/java/lld/fooddelivery/OrderConcurrencyTest.java`):

```java
@RepeatedTest(50)
void partnerAssignmentRacingWithCancellationNeverLeavesAStrandedReservation() throws InterruptedException {
    Order order = freshOrder("race-order-2");
    order.accept();
    DeliveryPartner partner = new DeliveryPartner("rider-1", "Asha", someLocation, PartnerStatus.AVAILABLE);
    DeliveryAssignmentService assignmentService = new DeliveryAssignmentService(new NearestAvailablePartnerStrategy());
    assignmentService.registerPartner(partner);

    CountDownLatch startGate = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    AtomicBoolean assigned = new AtomicBoolean(false);

    pool.submit(() -> { await(startGate); assigned.set(assignmentService.assignPartnerFor(order, someLocation).isPresent()); });
    pool.submit(() -> { await(startGate); order.cancel("customer changed their mind"); });

    startGate.countDown();
    pool.shutdown();
    pool.awaitTermination(5, TimeUnit.SECONDS);

    assertEquals(OrderStatus.CANCELLED, order.status());
    if (!assigned.get()) {
        assertEquals(PartnerStatus.AVAILABLE, partner.status()); // never stranded as BUSY
    }
}
```

**Verified test run (this repository, this change):** 141 tests, 141 passed, 0 failed, 0
skipped (12 test containers), via
`java -jar junit-platform-console-standalone.jar --class-path build/classes --scan-class-path`.

---

## 16. Trade-offs and Alternatives

| Decision | Chosen approach | Alternative | Pros/cons | When the alternative wins |
|---|---|---|---|---|
| Order lifecycle enforcement | State pattern (polymorphic dispatch) | `switch(status)` in `Order` itself | State: more classes, but each is independently testable/reviewable and the compiler can't "forget" a case silently (default-throw). Switch: fewer files, but the match arms grow unboundedly and nothing stops a developer from adding a case that mutates state without checking legality. | A 2–3-status lifecycle with symmetric transitions (e.g. a simple on/off toggle) — State pattern would be pure ceremony there. |
| Delivery-partner reservation | Lock-free CAS on `AtomicReference<PartnerStatus>` | `synchronized` method on `DeliveryPartner` | CAS: no blocking, trivially composes with a retry loop over multiple candidates. `synchronized`: simpler to read for engineers unfamiliar with CAS, but you'd hold/release a lock per failed candidate in the assignment retry loop — strictly more overhead. | If reservation logic ever needed to be a multi-field, multi-step atomic unit (not just one status flip), `synchronized` becomes the right call again (as it is for `Order` itself — see §11.6). |
| Pricing composition | Decorator pipeline built by the caller at runtime | A fixed `PricingPolicy` enum with hard-coded combinations | Decorator: arbitrary runtime composition, one class per *rule* not per *combination*. Enum: simpler for a *fixed*, *small* set of named promos a business team configures without code changes. | If discounts are managed by a non-engineering team via a config table ("promo codes"), a data-driven rules engine (not even an enum) might be worth the complexity instead of either. |
| Order's `discount`/`deliveryFee` breakdown | Store only `subtotal` and `total`; the decorator pipeline's internal breakdown is not retained | Have every decorator also emit a `{label, delta}` record for a line-item invoice | Simpler `Order` (fewer fields, no need to keep two parallel pipelines in sync); con: no itemized invoice without re-deriving it. | A real product absolutely needs an itemized invoice ("why was I charged ₹490?") — this would be the very next feature built, by having each decorator append to a shared `List<PriceAdjustment>` passed through `PricingContext`, a small, additive change. |
| API shape | Small purpose-built method parameters (`placeOrder(customerId, pricingPipeline, ...)`) | A single `PlaceOrderRequest` DTO | Purpose-built params: self-documenting call sites, no DTO-field-nullability ambiguity. DTO: one object is easier to version/extend at a REST boundary and easier to log/serialize as one unit. | The moment this is exposed over a real network API (not just an in-process Java call), a request DTO wins for serialization/versioning reasons — see §17. |
| Payment idempotency | In-process CAS (`AtomicReference<PaymentStatus>`) keyed by order id | An external idempotency-key table with a TTL | In-process CAS: zero extra infrastructure, perfectly correct within one process's lifetime. External table: survives process restarts and works across multiple service instances. | The instant payment processing is horizontally scaled across multiple instances/processes (§17), the in-process CAS stops being sufficient and the external table (or a DB `UPDATE ... WHERE status='SUCCESS'`, per §12) becomes mandatory. |

---

## 17. Production Evolution

Kept explicitly separate from the core interview solution above — these are the things a
*real* production rollout needs that are deliberately not built here:

- **Persistence:** move every `ConcurrentHashMap` registry to the relational schema in §12,
  with the optimistic-version-column pattern replacing in-process locks/CAS.
- **Durable scheduling:** replace `ScheduledExecutorService`-based acceptance timeouts with a
  durable mechanism (delayed queue message, or a periodic sweep job over `orders` rows) so a
  process restart doesn't silently lose pending timeouts.
- **Event-driven notifications:** replace direct `OrderObserver` method calls with publishing
  `OrderEvent`s onto a message bus (Kafka/SNS), letting customer-push, restaurant-dashboard, and
  rider-app notification delivery scale and retry independently of order processing.
- **Monitoring/metrics:** emit counters/histograms per state transition (e.g.
  `order.accept.latency`, `order.autocancel.count`), per-assignment-attempt success/failure
  rates, and payment success/failure rates; alert on acceptance-timeout rate spikes (signals
  restaurant-side problems) and on assignment-failure rate spikes (signals rider-supply
  shortage in a region).
- **Logging:** structured, correlation-id-tagged logs around every `Order` transition
  (`OrderEvent` is already shaped to become a structured log line verbatim).
- **Distributed coordination:** partition by city/region (see §14); use a distributed lock
  manager or DB-row-level locking (not in-process `synchronized`) once multiple service
  instances can touch the same order concurrently.
- **Fault tolerance:** circuit-break and retry-with-backoff around the real payment
  gateway/routing-ETA service calls that today are simulated in-process
  (`PaymentService.charge`, `GeoLocation.distanceKmTo`); add a dead-letter path for orders that
  exhaust all assignment retries.
- **Operational concerns:** rate-limit `placeOrder` per customer (abuse/fraud prevention); add
  an admin/ops override path to manually force-cancel or manually-assign a partner for a stuck
  order; add audit logging (who force-cancelled, when, why) separate from the customer-facing
  `OrderEvent` history.
- **API evolution / backward compatibility:** once exposed over REST/gRPC, version the
  `PlaceOrderRequest` shape explicitly (additive fields only within a major version); keep
  `OrderStatus` as an open-ended string on the wire (not a closed enum) so new statuses can be
  added without breaking old mobile-app clients that don't recognize them (they'd simply show a
  generic "processing" state for unknown values).
