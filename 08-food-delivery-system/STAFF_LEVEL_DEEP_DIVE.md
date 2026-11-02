# Staff-Level Deep Dive — Food Delivery System

These are the questions a Staff/Principal-level interviewer uses to pressure-test the design
in this repository specifically — not generic LLD trivia. Each answer reasons from the actual
code in `src/main/java/lld/fooddelivery/`.

---

### 1. You chose `cancel()` (not `reject()`) as the transition that races with delivery-partner assignment, and you locked that in via the state machine (reject only legal pre-acceptance). What happens the day product says "actually, restaurants need to be able to back out even mid-preparation, with a penalty"?

This is exactly the kind of requirement change the State pattern is meant to absorb cheaply —
but it's worth being honest about *where* the cost actually lands. Adding "restaurant backs out
during `PREPARING`" means: (1) `PreparingState` gains a `cancel()` override (one new method,
~5 lines) — cheap. (2) The *harder* part is everything downstream that assumed cancellation
only happens pre-cook: `OrderService.cancelOrder` currently just releases a delivery partner if
one is assigned; now it also needs to decide whether to still charge a cancellation fee/penalty
to the restaurant, which is a new cross-cutting concern that doesn't belong in `Order` itself
(it's a billing/penalty rule, not a lifecycle rule). The honest answer: the *state machine* is
cheap to extend; the *business logic that hangs off the transition* is not automatically free
just because the pattern is flexible, and conflating "pattern makes it easy" with "requirement
is free" is a real trap.

### 2. Your delivery-partner reservation is a single CAS on one `AtomicReference<PartnerStatus>`. What breaks the day a rider needs to carry *two* orders at once (batched delivery, a real Swiggy/Zomato feature)?

Everything about `PartnerStatus` being a two-state-ish `AVAILABLE`/`BUSY` boolean breaks. A
rider carrying N orders needs either (a) a `BUSY(capacity_used, capacity_max)` richer status, or
(b) `DeliveryPartner` tracking a `Set<orderId>` instead of a binary flag, with `tryReserve()`
becoming "reserve iff `currentLoad < maxCapacity`" — which is no longer expressible as a simple
two-value CAS; it needs either a CAS loop on an integer counter (`compareAndSet(current,
current+1)` guarded by `current < max`) or a short `synchronized` section, trading "lock-free"
for "correct under a richer invariant." This is a real architectural seam I'd flag up front as
"today's design assumes one order per rider at a time" rather than let it be discovered later.

### 3. Walk through exactly what happens if the process crashes between `paymentService.charge(order)` succeeding and `orders.put(order.id(), order)` running in `OrderService.placeOrder`.

Look at the actual code: `charge()` runs, succeeds, and **then** `orders.put(...)` runs. If the
process dies in that gap, the customer has been charged but no `Order` object exists anywhere —
a true "charged but no record" bug, the worst possible outcome for a payment system. This is a
real, not hypothetical, gap in the current in-memory design, and it's worth naming unprompted
rather than waiting to be caught: the fix is either (a) persist the `Order` *before* charging
(status `PENDING_PAYMENT`) and only transition to `PLACED` after charge succeeds, so a crash
leaves a recoverable "we have a record, go check with the payment gateway what actually
happened" row instead of silent money loss, or (b) make the whole `placeOrder` sequence a single
database transaction in the persistent version (§12 of the README) so "charged but no order
row" becomes impossible by construction. In the in-memory demo this is unaddressed by design
(no crash recovery story is claimed anywhere) — but I would not want to present this code as
production-ready without calling that gap out myself.

### 4. `Order.history` and `OrderTrackingObserver` both grow by appending events for the lifetime of the order. At 10 million daily active orders, what's the first thing that falls over?

Not the per-order history itself (bounded at ~7 events by the state machine's diameter) — it's
the **registries**: `orders`, `paymentsByOrderId`, `acceptanceTimeoutTasks` are all
`ConcurrentHashMap`s that are never evicted in this code. At 10M orders/day with no TTL/archival,
heap grows unboundedly within a single process — this would OOM in hours, not days. The fix
isn't "add more concurrency primitives," it's an operational one: completed/terminal orders
(`DELIVERED`/`CANCELLED`/`REJECTED`) need to be archived out of the hot in-memory map to cold
storage (or, in the real persistent design, simply live as DB rows with no in-memory residency
requirement at all) on a schedule (e.g., 1 hour after reaching a terminal state). This is a
capacity-planning problem the current code structurally does not address, and I'd say so
directly rather than claim the `ConcurrentHashMap` design "scales."

### 5. Your `DeliveryAssignmentService.assignPartnerFor` loop retries against a shrinking candidate list on CAS failure. What's the worst-case number of iterations, and is there a livelock risk?

Worst case is O(n) iterations (one CAS attempt consumed per loop, `tried` growing by exactly one
element each failed attempt, guaranteeing termination — this is not a spin-loop retrying the
*same* candidate, it strictly removes the failed candidate from future consideration). So no
livelock: the loop is bounded by the candidate-pool size and always terminates, either with a
successful reservation or an empty pool. The real risk is elsewhere: under very high contention
(many orders accepted in the same instant competing for a small pool of nearby riders), you get
O(n²) total CAS attempts system-wide (each of n orders potentially tries up to n riders before
succeeding or exhausting the pool) — a genuine throughput concern at high order-acceptance
velocity in a small geofence, worth flagging even though it's not a correctness bug.

### 6. You picked per-`Order` `synchronized` over a single global order-lock. What's the actual evidence that per-order locking is "right," versus just an assumption?

The evidence is in the access pattern, not an assumption: any two `Order` instances are touched
by *disjoint* sets of concurrent actors (the only actors that ever touch order A are A's
customer, A's restaurant, A's assigned rider, and the acceptance-timeout task scheduled
specifically for A) — there is no code path anywhere in this repository that acquires two
different `Order` monitors in the same call stack. Given that, a global lock would only ever
*add* contention (order #42's restaurant accepting would block order #99's rider picking up)
with zero correctness benefit, since the two orders never share mutable state. If a future
requirement introduced *cross-order* invariants (e.g., "a rider can carry at most 2 orders," per
question 2) that evidence would change, and a coarser lock — or a lock scoped to the rider,
not the order — would become necessary for *that* invariant specifically.

### 7. Could you add "scheduled orders" (order now, deliver at 7pm) without touching the existing `OrderState` classes?

Mostly yes, with one honest caveat. The happy path (an order simply has a future
`scheduledDeliveryTime` field and the acceptance-timeout logic is suppressed/delayed until
closer to that time) doesn't require changing any `OrderState` subclass — it's new data on
`Order` plus a new scheduling policy in `OrderService`, which is exactly the kind of additive
change the state machine should tolerate. The caveat: if "scheduled" needs to be a visibly
*different* status in the UI (so customers/restaurants can tell a scheduled order apart from an
immediate one before it's accepted), that's a genuinely new state (`SCHEDULED` preceding
`PLACED`), which *does* mean touching the enum and adding a class — acceptable, bounded,
Open/Closed-respecting growth, but not literally zero-touch.

### 8. Your `Cart` is `synchronized` per-method, not synchronized as a whole multi-step operation. Is there a TOCTOU gap between `cartFor(customerId).subtotal()` and `placeOrder` reading cart items?

Yes, and it's a real, acknowledged gap, not a hidden one: `OrderService.placeOrder` calls
`cart.items()` (a synchronized snapshot) and `cart.subtotal()` (a second, separate synchronized
call) — between those two calls, another thread (the same customer's second browser tab) could
mutate the cart, so the subtotal and the items list are not guaranteed to be drawn from the
exact same instant. In practice this is low-risk because a cart is single-customer-owned (no
cross-customer race, and a customer racing against themselves across tabs is a rare, low-stakes
edge case — worst case they see a slightly stale subtotal, never someone *else's* order
corrupted). The *fully* correct fix is a single `Cart.snapshot()` method returning `(items,
subtotal)` captured atomically under one `synchronized` block, which I'd implement before
shipping this to production, but which I consciously did not add here to keep `Cart`'s API
surface minimal for the interview.

### 9. Why is `MenuItem.available` an `AtomicBoolean` instead of being protected by `Menu`'s own lock?

Because `Menu` doesn't need to coordinate *across* items — toggling item A's availability has
zero interaction with item B's availability, so making `Menu` hold a lock for single-item
toggles would serialize unrelated items' updates against each other for no benefit. Pushing the
atomicity down to the smallest object that actually owns the invariant (`MenuItem` owns its own
`available` flag) is a deliberate granularity choice, not an accident — the alternative
(`Menu`-level lock) is strictly worse concurrency for identical correctness.

### 10. Two concurrent `placeOrder` calls for the *same customer* both read `cart.isEmpty() == false`, both proceed to checkout. Can the customer end up double-charged for the same cart?

Walk the code: both threads would call `cartService.clear(customerId)` idempotently (clearing
an already-empty cart is a no-op), but *before* that, each thread independently builds its own
`List<OrderItem>` snapshot from `cart.items()` and calls `paymentService.charge(order)` with its
*own newly-constructed* `Order` object (unique order id from `IdGenerator`). There is no
dedup key tying "this specific cart's contents" to "an order, exactly once" — so yes, this is a
genuine double-order (and double-charge) bug under concurrent duplicate submission from the same
customer (e.g., a frantic double-tap on "Place Order" with no client-side debounce, or a client
retry after a timed-out-but-actually-succeeded request). The fix is an idempotency key supplied
by the client (e.g., a client-generated UUID per checkout *attempt*, not per retry), used the
same way `PaymentService.charge` already dedups *by order id* — this repository's `Payment`
idempotency protects against duplicate *payment* calls for an *already-created* order; it does
not protect against duplicate *order creation* from the same cart, which is a related but
distinct gap, worth being precise about rather than conflating the two.

### 11. If you had to add a totally new discount type tomorrow — say, "₹X off if paid via a specific wallet" — do you touch any existing decorator class?

No — that's the whole point of the Decorator pattern applied here: a new
`WalletPaymentDiscountDecorator` is a new class implementing `PriceCalculator`/extending
`PriceCalculatorDecorator`, wired into the pipeline at the call site
(`demo/FoodDeliveryDemo` or wherever `OrderService.placeOrder` is invoked from). Nothing in
`PercentageDiscountDecorator`, `FlatDiscountDecorator`, or `BaseSubtotalCalculator` changes. The
one thing that *would* need to change is `PricingContext`, which would need a new field (e.g.
`paymentMethod`) for the new decorator to read — and that field addition is itself backward
compatible (a `record` gaining a field is a source-breaking but not logic-breaking change; every
existing decorator simply ignores the new field).

### 12. Your `OrderService` constructor takes both a `ScheduledExecutorService` for timeouts and a plain `Executor` for assignment — in the demo, they're literally the same thread pool. Is that a real design choice or an oversight?

A real, if understated, choice: they're accepted as *separate* parameters specifically so a
real deployment can give them different sizing/isolation (e.g., a small dedicated scheduler
thread pool for timeouts, separate from a larger worker pool for assignment, so a burst of
assignment work never delays timeout firing, and vice versa) — but the demo wires them to the
same pool because for an in-memory interview-scale demo that distinction doesn't matter and
introducing two separate executors would just be noise. This is worth naming proactively: "I
made these two parameters, not because the demo needs two pools, but because the *interface*
should not force a caller into shared-pool contention once it matters."

### 13. What's the actual consistency guarantee you can state about `order.status()` as observed from a thread that is *not* holding the order's monitor (e.g., a read-only tracking API)?

Precisely: it's a **volatile read**, so it is guaranteed to observe the most recently
*completed* `synchronized` write (no stale-forever reads, thanks to `volatile`'s happens-before
relationship with the preceding `synchronized` block's exit) — but it offers **no linearizability
guarantee relative to a concurrently-in-flight transition**: a reader could observe `ACCEPTED`
a microsecond before a concurrent `cancel()` call completes and the status becomes `CANCELLED`
a microsecond later. This is the correct and sufficient guarantee for a *read-only tracking
screen* (eventually-consistent-but-never-stale-forever is exactly what "where's my order"
needs) — it would **not** be sufficient for, say, a second writer trying to make a decision
based on that read without its own synchronization (which is exactly why
`assignDeliveryPartner` doesn't trust a prior read of `status()` — it re-checks under its own
`synchronized` block instead of trusting a volatile read taken earlier).

### 14. If this needed to run across 3 service instances behind a load balancer tomorrow, what's the single riskiest line of code in this repository?

`private final ConcurrentHashMap<String, Order> orders` (and its siblings in every other
service). The instant there's more than one JVM, in-process locks/CAS on these maps stop
providing any cross-instance guarantee at all — two instances could each believe they
successfully reserved the same delivery partner, because each instance's `AtomicReference` is
local memory, not shared state. This is the single architectural assumption this whole
concurrency story rests on ("there is exactly one process"), and I would say that out loud
unprompted — the §12/§17 discussion of moving to DB-row-level optimistic locking exists
specifically because this assumption breaks the moment you horizontally scale.

### 15. Your acceptance-timeout uses `ScheduledExecutorService.schedule(..., acceptanceTimeout, ...)`. What happens if the restaurant accepts at, say, 99% of the timeout window — is there a race between "accept barely in time" and "timeout fires anyway"?

Walk it through: `acceptOrder` calls `cancelAcceptanceTimeout(orderId)` (which calls
`future.cancel(false)`) **before** calling `order.accept()`. If the timeout task has *already
started running* by the time `cancel(false)` is called, `cancel(false)` does not interrupt it
(the `false` means "don't interrupt if running") — so there's a genuine narrow window where both
`autoCancelIfStillUnanswered` and `acceptOrder`'s `order.accept()` could both be mid-flight. But
look at what actually happens next: `autoCancelIfStillUnanswered` itself re-checks
`order.status() != OrderStatus.PLACED` and bails out if so — and because both `accept()` and
`cancel()` ultimately synchronize on the same `Order` monitor, whichever one's critical section
completes first wins outright and the other observes the post-transition status correctly (the
timeout task's status check happens *before* it calls `order.cancel(...)`, and if `accept()` won
the race, the status is already `ACCEPTED`, so the timeout task's own guard clause prevents it
from ever calling `cancel()` at all). So: no actual corruption is possible, but there is a real
narrow window where the timeout task *runs* (burns a thread momentarily) even though it
ultimately no-ops — a performance footnote, not a correctness bug, and worth distinguishing the
two explicitly when asked.

### 16. You floor discounts at zero via `Money.subtractFloored`. What if a staff engineer reviewing this says "that silently hides a bug — if a discount ever exceeds the subtotal, I want to know, not have it quietly clamp to zero"?

That's a legitimate alternative design position, not a wrong one, and worth engaging with
directly rather than defending the current choice reflexively: clamping is the right choice for
*legitimate* stacking scenarios (a ₹100-off coupon applied to a ₹80 order should obviously just
mean "free," not an exception), but it does mean a genuine configuration bug (e.g., a coupon
misconfigured as "₹10,000 off" by a typo) fails silently instead of loudly. The reconciled
position: clamp at the `Money` arithmetic level (as now, because "never go negative" is a hard
domain invariant regardless of *why* a discount got large), but add a *separate*,
higher-level sanity check in the pricing pipeline's caller — e.g., "if total discount exceeds
50% of subtotal, log a warning/alert" — which catches the misconfiguration case without
weakening the arithmetic-level invariant. This is a good example of a review comment that's
right about a real gap without being right that the current behavior itself is wrong.

### 17. Your `RoundRobinPartnerStrategy` exists in the codebase but isn't used anywhere except its own test. Why include unused production code at all — doesn't that violate YAGNI?

Fair challenge, and the honest answer is that it's there for a specific, narrow reason: to
*prove*, concretely, that `DeliveryAssignmentService` doesn't need to change when the
assignment policy changes — i.e., to demonstrate the Strategy pattern actually buys something,
rather than just asserting it would in a comment. In a real production codebase I would *not*
ship a second, unused strategy implementation merely to prove a point — that genuinely would be
YAGNI violation and unnecessary surface area to maintain and keep tested. I'd flag this
explicitly as "this class exists for pedagogical completeness in this interview-prep repository,
and would not survive a real code review as dead code without an active caller."

### 18. What's the actual time complexity of `OrderService.placeOrder`, end to end, and where's the dominant term?

O(k) where k is the number of distinct items in the cart: one `ConcurrentHashMap` lookup per
item for the availability re-check (`restaurant.menu().isItemAvailable`), one
`OrderItem` construction per item, and the decorator pipeline's `calculate()` call is O(1) per
decorator layer (a handful of decorators, not proportional to cart size). There's no hidden
O(n) scan over *other* orders or *other* customers' carts anywhere in the method — the whole
operation only ever touches data scoped to the one customer/cart/restaurant in question, which
is exactly the property you want for a method that needs to stay fast regardless of total
platform order volume.

### 19. If you had exactly 2 weeks to ship a simplified version of this to real users, what would you cut first, and what would you refuse to cut?

**Cut first:** the Decorator pricing pipeline (ship one hard-coded flat discount rule inline;
defer composability until a second real promo type actually exists — premature
generalization is exactly the overengineering risk this pattern carries if introduced before
it's needed), the `RoundRobinPartnerStrategy` alternative (ship nearest-available only), and
any notion of an itemized invoice breakdown. **Refuse to cut:** the State-pattern-enforced order
lifecycle (a shipped product with an ad-hoc status field and no transition guard rails is a
guaranteed source of "impossible" production bugs within weeks) and the CAS-based delivery-
partner reservation (double-booking a real human rider is a trust-destroying, not just
annoying, bug — this is the one piece of concurrency correctness I would not approximate even
under time pressure).

### 20. Does the Observer pattern here create a hidden coupling problem — can a slow/misbehaving `OrderObserver` block an order's state transition?

Yes, and this is a real, specific weakness worth naming rather than glossing over:
`Order.notifyObservers(event)` is called from *inside* the same `synchronized` method that
performs the transition (`transition(...)` and `assignDeliveryPartner(...)` both call
`notifyObservers` before returning, still holding the order's monitor). If any registered
`OrderObserver.onOrderUpdated` implementation blocks (e.g., a notification observer doing a
synchronous, slow network call to a push-notification provider), it directly stalls every other
thread waiting on that same order's monitor — including, in the worst case, the very next
status transition for that order. The fix in a production system: observers should enqueue
work onto an async channel (a queue, an executor) rather than doing blocking I/O directly inside
`onOrderUpdated`, or `Order` should snapshot its observer list and fire notifications *after*
releasing its own monitor. This repository's demo observers (`CustomerNotificationObserver`,
etc.) are intentionally trivial `System.out.println` calls specifically so this risk never
actually manifests in the tests/demo — but it is a latent footgun in the current design that
I would flag explicitly before letting a real, slow observer get registered against it.

### 21. Why does `Order` reference its customer/restaurant/delivery-partner purely by `String` id rather than holding object references — and what does that cost you?

It decouples `Order`'s lifetime and serialization from the lifetime of those other aggregates —
you can load/save/transmit an `Order` without dragging the entire object graph of its restaurant
and every other order that restaurant has ever had along with it, which matters the moment this
becomes a persisted, networked system (object references don't survive a process boundary
cleanly; ids do). The cost: every read that needs restaurant/customer/partner *data* (not just
the id) has to go back through the relevant service (`restaurantService.requireRestaurant(...)`),
which is an extra map lookup per access rather than a free in-memory pointer dereference — a
deliberate, standard trade of a small constant-time cost for avoiding tight coupling between
aggregates, consistent with how most real systems model foreign keys rather than object graphs
across aggregate boundaries.

### 22. Is there anything about this design that would be hard to reverse later — an architectural decision you'd want to get right the first time because changing it later is expensive?

Two things, honestly assessed: (1) the decision that `Order.assignDeliveryPartner` and
`Order.cancel`/etc. share **one** lock (the order's own monitor) rather than finer-grained
per-field locks — reversing this later (splitting into separate locks) would require
re-deriving every cross-field invariant currently guaranteed "for free" by the single
critical section, which is exactly the kind of subtle concurrency bug that's expensive to
retrofit correctly. (2) the decision that `OrderItem`/pricing totals are opaque (`Money
subtotal`, `Money total`, no itemized breakdown) — this is cheap to reverse *additively*
(add a breakdown list alongside the existing fields) but if any downstream consumer (a receipt
PDF generator, an analytics pipeline) comes to depend on `total - subtotal` as "the discount
amount" via inference rather than an explicit field, that implicit contract becomes
surprisingly hard to change later without breaking a consumer nobody remembered depended on it
— which is why I'd push, in a real system, for an explicit (if initially empty/single-element)
breakdown list from day one rather than letting consumers infer structure from two scalar
totals.
