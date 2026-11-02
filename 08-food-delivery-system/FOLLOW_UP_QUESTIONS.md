# Follow-Up Questions — Food Delivery System

Realistic interviewer follow-ups, grouped by category, with full answers. These are deliberately
distinct from `STAFF_LEVEL_DEEP_DIVE.md` — that file is about hard, problem-specific design
trade-offs; this file is the broader "can you explain the fundamentals as they show up in this
code" pass an interviewer uses to sanity-check breadth.

---

## Object-Oriented Design

**1. Why is `Order` the aggregate root rather than, say, `Restaurant`?**
Because `Order` is the entity whose invariants (valid status transitions, exactly-one
delivery-partner-at-a-time, payment consistency) must be enforced atomically and which has its
own independent identity and lifecycle that outlives any single request. `Restaurant` is
referenced *by* orders but doesn't need to coordinate invariants across them — two orders for
the same restaurant don't need to be mutually consistent with each other the way an order's own
status and delivery-partner-id do. The aggregate root is whichever object owns the invariant
that needs a single consistency boundary; here that's unambiguously `Order`.

**2. Why does `Cart` hold mutable `CartItem`s but `Order` hold immutable `OrderItem`s?**
Different lifecycle stages need different mutability. A cart is actively being edited
(quantities change, items get removed) right up until checkout — mutability is the *correct*
model for that, not a shortcut. The moment checkout happens, the business needs a permanent,
unchangeable record of exactly what was ordered at what price, independent of any future menu
price change or cart edit — so `Order` deliberately snapshots into a new, immutable type
(`OrderItem`) rather than reusing `CartItem` by reference. This is composition over sharing
mutable state across two objects with fundamentally different consistency requirements.

**3. Why is `GeoLocation` a value object and not an entity?**
It has no identity of its own — two `GeoLocation(12.9, 77.6)` instances are completely
interchangeable and should be `equals()` by value, not by reference. It's never looked up,
updated in place, or referenced by an id; it's always owned by (embedded in) whichever entity
has a position (`Restaurant`, `DeliveryPartner`). That's the textbook distinguishing test for
entity vs. value object: "does this thing need to be the *same* object across time, or just
equal in value?"

**4. What's the single responsibility of `DeliveryAssignmentService`, stated precisely?**
"Given an order that is eligible for delivery-partner assignment, find and reserve exactly one
available, suitable partner for it, or fail cleanly." It deliberately does *not* know how
pricing works, does not know how payment works, and does not decide *when* assignment should be
triggered (that's `OrderService`'s job, which calls it asynchronously after acceptance) — a
textbook SRP boundary: one reason to change this class (the assignment algorithm or reservation
mechanics changing), not several.

**5. Why does `Menu` wrap a `ConcurrentHashMap` instead of exposing it directly?**
Encapsulation of the data structure choice: callers depend on the behavior ("get an item by id,"
"check availability," "list items"), not on "this happens to be backed by a hash map today."
If `Menu` later needed to maintain items in a sorted/insertion order, or needed a secondary index
by category, that change stays entirely internal to `Menu` and doesn't ripple to every caller
that would otherwise be holding a raw map reference.

---

## SOLID Principles

**6. Where does this design most clearly demonstrate the Open/Closed Principle?**
`PriceCalculator`/`PriceCalculatorDecorator`: adding a new discount or fee type is strictly
additive — a new class implementing the interface — with zero modification to
`BaseSubtotalCalculator` or any existing decorator. Equally, `OrderState`: adding a new terminal
state (hypothetically) would mean a new class implementing the interface, not editing the
existing state classes' logic.

**7. Where does Liskov Substitution actually matter in this codebase, concretely?**
Every concrete `OrderState` (e.g. `PlacedState`, `DeliveredState`) must be fully substitutable
wherever `OrderState` is expected — `Order.transition(...)` calls methods like
`state.accept(this)` without ever checking "which concrete state is this," and correctness
depends entirely on every implementation honestly either performing the transition or throwing
the documented exception, never silently doing something unexpected. If `DeliveredState.cancel`
silently no-op'd instead of throwing, that would violate LSP even though it compiles fine —
callers reasonably assume "no exception" means "the cancellation happened."

**8. Does `Order` itself violate the Single Responsibility Principle by owning both state-machine
orchestration *and* observer notification?**
Arguably a reasonable, bounded violation rather than a clean pass: `Order` both enforces its own
lifecycle invariants *and* fans out notifications about changes to that lifecycle. The
justification for keeping them together is that notification is inseparable from the
transition's atomicity — you want "transition happened" and "observers were told" to occur
under the same lock so no observer ever sees a stale or skipped event. A stricter SRP reading
would extract notification into a separate `OrderEventPublisher` that `Order` hands its
observer list to, called immediately after (not during) the synchronized block — a legitimate
refactor I'd consider if `Order` grew more responsibilities, but not clearly worth the extra
indirection at its current size.

**9. Which classes would be "closed for modification" the longest under reasonable future
requirements, and which are "load-bearing" enough that you'd expect to touch them often?**
`Money`, `GeoLocation`, the exception classes, and the `OrderState` interface itself are the
most stable — they encode fundamental domain concepts unlikely to change shape. `OrderService`
is the most likely to be touched repeatedly, because it's the orchestrator where new
cross-cutting requirements (loyalty points, batched delivery, scheduled orders) naturally land
first, which is expected and fine for an orchestration-layer class — it's supposed to be where
composition happens.

**10. Give a concrete example of Dependency Inversion in this codebase.**
`OrderService` depends on the `DeliveryPartnerAssignmentStrategy` interface (via
`DeliveryAssignmentService`), not on `NearestAvailablePartnerStrategy` directly — high-level
orchestration policy doesn't depend on a specific low-level algorithm, both depend on the
abstraction. Same pattern for `PriceCalculator`: nothing in `OrderService` references
`PercentageDiscountDecorator` by name; it receives a fully-assembled `PriceCalculator` from
outside (constructed by the caller/demo), which is also an example of dependency injection via
constructor/parameter rather than internal `new`.

---

## Design Patterns

**11. Why State over a simple `enum OrderStatus` with a big `switch` in `Order`?**
A switch-based approach puts all transition logic in one method that grows linearly with every
new status and every new transition rule, and nothing stops a maintainer from forgetting a case
(a missing `default` silently allows an illegal transition, or a present-but-wrong `default`
throws for a case that should've been handled). State scatters that same logic across small,
individually-testable classes where "this transition isn't listed" *is* the absence of an
override, which the interface's default method turns into a guaranteed, uniform exception — you
cannot forget to handle an illegal case because the default IS the illegal case.

**12. Why Strategy over a boolean flag like `useRoundRobin`?**
A flag approach caps you at exactly the algorithms someone anticipated and hard-codes them
inside the service via conditionals; adding a third algorithm means editing that service's
branching logic again. Strategy makes the algorithm itself the unit of extension — a new
`PartnerAssignmentStrategy` implementation is a wholly new class, and the service that uses it
never needs to change, satisfying Open/Closed in a way a boolean flag fundamentally cannot.

**13. Why Decorator over just computing discounts with a loop over a `List<DiscountRule>`?**
Both are defensible; Decorator was chosen because discounts here *compose by wrapping a
calculation*, not just by applying independent deltas to the same number — e.g., a percentage
discount needs to decide whether it applies to the raw subtotal or to a previously-adjusted
amount, which is naturally expressed as "wrap the previous calculator and decide what you do
with its result" rather than "iterate a list and sum deltas." A `List<DiscountRule>` approach
would work fine for purely additive/independent rules, but starts needing extra bookkeeping
(ordering rules, base-vs-running-total flags) the instant a rule needs to know about a
*previous* rule's output — which percentage-discount-capped-at-max already does here.

**14. Why Observer instead of just having `OrderService` call each notification channel
directly after a transition?**
Because `OrderService` shouldn't need to know how many notification channels exist or what they
do — today it's three (customer, restaurant, delivery-partner), tomorrow it might be five
(analytics, fraud-detection, a support-ticket auto-updater). Observer lets each channel register
itself with the thing that's actually changing (`Order`) without `OrderService` maintaining a
hard-coded list of "everyone who needs to know" and without `Order` or `OrderService` needing to
change when a new channel is added — strictly additive, same Open/Closed benefit as the other
two patterns.

**15. Did you consider a Factory pattern anywhere, and why isn't one used?**
Considered and deliberately not used for `Order` construction: `Order`'s constructor already
has a small, fixed parameter list built entirely from data already validated by `OrderService`
(cart items, restaurant id, customer id, computed total) — there's no polymorphic construction
logic (no "which concrete type of Order to build" decision) that would justify a Factory's
indirection. A Factory earns its complexity when object creation itself has branching logic or
hides a complex assembly sequence; here construction is a single straightforward constructor
call, so introducing a Factory would be unjustified indirection.

---

## Data Structures

**16. Why `ConcurrentHashMap` for every registry rather than `Collections.synchronizedMap` over
a `HashMap`?**
`ConcurrentHashMap` provides lock striping (fine-grained internal locking per bucket segment)
so unrelated keys don't contend with each other, plus atomic compound operations
(`computeIfAbsent`, `putIfAbsent`) that are used throughout this code (e.g.,
`CartService.cartFor` uses `computeIfAbsent` to atomically get-or-create a cart without a
separate check-then-act race). `Collections.synchronizedMap` wraps a plain `HashMap` with a
single coarse lock for the *entire* map and still requires external synchronization for
compound operations like get-then-put, which would reintroduce exactly the check-then-act race
`computeIfAbsent` avoids here.

**17. Why `CopyOnWriteArrayList` for `Order.observers` and `OrderTrackingObserver`'s events,
rather than a plain synchronized `ArrayList`?**
Both are write-rare, read/iterate-frequent: observers are registered once at setup and then
iterated on every single transition; tracking events are appended occasionally and potentially
read/polled far more often. `CopyOnWriteArrayList` gives lock-free, snapshot-consistent
iteration (no `ConcurrentModificationException` risk even if a registration happened mid-
iteration) at the cost of an O(n) copy per write — an excellent trade here because writes
(new observer registrations, new events) are rare relative to reads.

**18. Why does `NearestAvailablePartnerStrategy` use a linear `Stream.min()` scan rather than a
spatial index (k-d tree, geohash grid)?**
Because at the scale modeled here (a single restaurant's candidate pool of nearby delivery
partners — tens, maybe low hundreds, not millions), a linear scan is both simpler and, in
practice, faster than the overhead of building/maintaining a spatial index for such a small
candidate set. A spatial index earns its complexity when the candidate pool itself is large
(e.g., "all riders in a whole city," not "riders already geofenced near one restaurant") — which
is exactly the kind of thing I'd introduce in the production-scale discussion, not in the
baseline design.

**19. Why is `Order.history` a plain `synchronizedList` rather than `CopyOnWriteArrayList` like
the observer list?**
A deliberate, smaller distinction: `history` is written exactly once per transition (same
frequency as transitions themselves, not "rare" relative to anything), so there's no
read-heavy/write-rare asymmetry to exploit the way there is for `observers`. A plain
synchronized list is the simpler, equally-correct choice here; reaching for
`CopyOnWriteArrayList` everywhere "for consistency" without the asymmetry that justifies it
would be needless cargo-culting of a specific data structure's trade-off into a place that
doesn't have the shape that trade-off is meant for.

**20. What's the amortized cost of `IdGenerator`'s id generation, and why `AtomicLong` over a
`synchronized` counter?**
O(1) per call either way, but `AtomicLong.incrementAndGet()` compiles to a single hardware CAS
instruction (or, on modern JVMs/CPUs, a fetch-and-add intrinsic) with no thread ever blocking —
strictly better throughput under contention than a `synchronized` block doing the same
increment, for an operation this simple (a single counter bump has no multi-step invariant that
would require broader mutual exclusion).

---

## Java Internals

**21. Why does `OrderState` use a `private` interface method (`invalid(...)`) — what Java
version does that require, and what does it buy you?**
Private interface methods require Java 9+ (this codebase targets 17+, well past that). It buys
shared, non-overridable helper logic across all the interface's default methods (every illegal-
transition default method builds the same exception shape) without exposing that helper as part
of the interface's public contract — callers/implementers can't see or override `invalid(...)`,
it's purely an internal implementation detail of the interface itself, which is exactly the
encapsulation a `private` (not `default`) interface method is for.

**22. Why are the concrete `OrderState` implementations singletons (`public static final X
INSTANCE`) rather than instantiated per-order?**
They're entirely stateless — `PlacedState` holds no per-order data, it only implements
behavior that's parameterized by the `Order` passed into each method. Since there's no instance
state to isolate between orders, allocating a new `PlacedState` per order would be pure waste;
sharing one immutable singleton across every order that happens to be in that state is both
safe (no mutable fields to race on) and avoids needless allocation at scale.

**23. `Money` overrides `equals`/`hashCode` — why does that matter here specifically, given it
wraps a `BigDecimal`?**
Because `BigDecimal.equals()` is scale-sensitive (`new BigDecimal("2.0").equals(new
BigDecimal("2.00"))` is `false`, since it compares scale as well as value), which is almost never
the semantics you want for money comparisons. `Money`'s custom `equals`/`hashCode` compares
*value* (via `compareTo() == 0` semantics, not `BigDecimal.equals`), so `Money.of("2.0")` and
`Money.of("2.00")` are correctly equal — a classic, easy-to-miss `BigDecimal` pitfall that's
worth calling out as deliberately handled, not accidentally avoided.

**24. Why `volatile` on `Order.state` and `Order.deliveryPartnerId` if every *write* to them
already happens inside a `synchronized` block?**
Because not every *read* does. Read-only callers like `order.status()` (used by tracking/
polling code) deliberately do **not** acquire the order's monitor, for throughput — a read-only
status check shouldn't have to contend with in-flight writers. Without `volatile`, such an
unsynchronized read would have no happens-before guarantee at all and could observe an
arbitrarily stale cached value indefinitely under the JMM; `volatile` guarantees every such read
sees the most recently *completed* synchronized write, which is exactly the guarantee a
read-only observer needs (see STAFF_LEVEL_DEEP_DIVE Q13 for the precision on what this does and
does not guarantee).

**25. Does using `records` for `OrderEvent` and `PricingContext` cost you anything compared to a
regular class?**
Functionally, almost nothing for these two types — both are small, genuinely immutable data
carriers with no need for custom validation logic beyond what's trivially addable in a compact
constructor, which is exactly the case records were designed for (auto-generated constructor,
accessors, `equals`/`hashCode`/`toString`, all value-semantics-correct by default). The one real
constraint is that records can't extend another class (only interfaces) and all fields are
implicitly `final` — neither is a limitation here since neither type needs inheritance or
mutability.

---

## Multithreading

**26. Could you deadlock by holding `Order` A's monitor while trying to acquire `Order` B's?**
No code path in this repository ever does that — every `Order` method only ever touches `this`
(its own fields/monitor); there is no method anywhere that takes two `Order` references and
synchronizes on both. The lock-ordering argument is trivial because there is effectively only
ever one lock in play per call, never two nested order locks.

**27. Could `DeliveryAssignmentService` deadlock against `Order`'s monitor?**
No — it follows a strict, single order: CAS-reserve the candidate partner first (lock-free,
no monitor involved), *then* enter the order's `synchronized` method. It never holds the order's
monitor while attempting the CAS, and never holds the partner's (there's no partner-level
monitor at all, only an atomic field) while blocked waiting to enter the order's monitor — so
there's no possibility of two threads each holding one resource and waiting on the other.

**28. What's the difference between the `tryReserve()` CAS on `DeliveryPartner` and the
`synchronized` approach on `Order`, and why use both styles in the same system?**
CAS is used where the operation is a single-field, single-step state change with a cheap,
well-defined "did it work" result (reserve iff currently AVAILABLE) that's naturally retriable in
a loop against *other* candidates on failure. `synchronized` is used where the operation spans
*multiple* related fields/decisions that must be observed and updated together atomically (an
order's status check and its delivery-partner-id write), which doesn't reduce cleanly to a
single CAS. Using CAS where `synchronized` is really needed would be unsafely papering over a
multi-field invariant; using `synchronized` everywhere CAS would do would leave needless
contention on the hot, simple, single-field reservation path. The system intentionally uses
each where it fits.

**29. What would `@RepeatedTest(50)` actually fail to catch, if anything?**
It's a statistical, not exhaustive, technique — it increases the *probability* of surfacing a
rare interleaving bug by re-running under JIT-warmed, scheduler-varying conditions, but it
cannot *prove* the absence of a race the way a formal model checker (e.g., a TLA+ spec or a
tool like JCStress for memory-model-level races) could. For genuinely rare interleavings (a
race requiring a context switch inside a 2-instruction window) 50 repetitions on typical CI
hardware might simply never surface it. This is why the tests here are written to make the
races *more* likely (explicit `CountDownLatch` start-gates forcing both threads to begin at
the same instant) rather than relying purely on repetition count to find trouble by luck.

**30. If you needed to add a *global* rate limit ("no more than 100 order-acceptances per
second"), where would that guard physically live, and would it need a new kind of lock?**
It would live in `OrderService.acceptOrder`, as a check against a shared, genuinely global
counter — and this is a case where a per-order lock structurally cannot help (the invariant
spans *all* orders, not one), so it would need either a dedicated atomic counter with a sliding
time window (e.g., a simple token-bucket implemented with `AtomicLong` timestamps, no
`synchronized` needed for a single counter) or, if the limit needs to be exact rather than
approximate under high concurrency, a small dedicated lock scoped only to the rate-limiter
object itself — importantly, *not* reusing any `Order`'s own monitor, to avoid coupling an
unrelated global concern to a per-entity lock.

---

## Database and Persistence

**31. This system is in-memory. What's the minimal schema change needed to make `Order`'s state
transitions safe under concurrent DB writers (no application-level lock available)?**
Optimistic concurrency via a `version` column: every transition does `UPDATE orders SET status =
?, delivery_partner_id = ?, version = version + 1 WHERE id = ? AND version = ?` and checks the
affected-row count — zero rows affected means someone else won the race, and the caller retries
or surfaces a conflict, exactly mirroring what the in-memory `synchronized` block guarantees
today, but expressed as a database-level CAS instead of a JVM monitor (see README §12 for the
full schema).

**32. Why would you *not* want a foreign key from `orders.delivery_partner_id` directly enforced
as `NOT NULL` with a hard constraint?**
Because an order legitimately has no delivery partner for its entire `PLACED`/most-of-`ACCEPTED`
lifetime (assignment happens asynchronously, sometimes a while after acceptance) — the column
needs to be nullable by design, not as an oversight, with the *application* (or a check
constraint tied to status) enforcing "must be non-null once PREPARING or later," rather than a
blanket `NOT NULL` that would make the natural pre-assignment state impossible to represent.

**33. Would you store `Order.history` as a separate table or as a JSON blob column?**
A separate `order_status_history` table (order_id, from_status, to_status, timestamp, note) —
not a JSON blob — specifically because this data needs to be independently queryable (e.g.,
"what's our average PLACED→ACCEPTED latency across all orders today" is a real operational
query you'd want SQL aggregation over), which a JSON blob column makes needlessly expensive
compared to a normalized, indexable table.

**34. How would you model the "exactly one active cart per customer" invariant at the DB level,
given `CartService` models it as `ConcurrentHashMap<customerId, Cart>` in memory?**
A `carts` table with `customer_id` as either the primary key or a unique constraint — the
uniqueness constraint *is* the "exactly one" invariant, enforced by the database itself rather
than application code, exactly mirroring how `computeIfAbsent` on a map keyed by customer id
enforces "at most one Cart object per customer" in memory today.

---

## Performance

**35. What's the realistic p99 latency contributor in `placeOrder`, assuming no network calls to
an external payment gateway (payment treated as synchronous but local)?**
The decorator pricing pipeline and item-availability loop are both O(k) in cart size and
effectively negligible (microseconds) for realistic cart sizes (single-digit to low-dozens of
items) — the dominant cost, once you introduce a *real* payment gateway, would overwhelmingly be
that network round-trip (tens to hundreds of milliseconds), not anything in this repository's
own logic. This is worth saying explicitly in an interview: don't over-optimize in-memory map
lookups when the actual bottleneck in a real deployment would be an external I/O call.

**36. Is `DeliveryAssignmentService.assignPartnerFor` a good candidate for running asynchronously
off the critical path, and why is it already structured that way?**
Yes, and `OrderService.acceptOrder` already dispatches it via the injected `Executor` rather
than running it inline — because the customer-facing "your order was accepted" response
shouldn't have to wait for a (potentially multi-candidate-retry) rider search to complete; the
acceptance itself is the fast, synchronous, customer-visible event, while assignment is a
best-effort background step that updates the order asynchronously and notifies observers when
it completes.

**37. If restaurant search/listing needed to support "nearby restaurants sorted by distance" at
scale, what would you change?**
Nothing about `Order`/state-machine code — this is purely a `RestaurantService` read-path
concern, and at real scale it would mean moving away from a `ConcurrentHashMap` linear scan
toward a geospatial index (PostGIS `ST_DWithin`/`ST_Distance` in Postgres, or a dedicated
geo-search service/Elasticsearch geo-query) — a read-side, horizontally-scalable concern
entirely orthogonal to the order-lifecycle concurrency story this repository focuses on.

---

## Failure Handling

**38. What happens if `PaymentService.charge` throws an unchecked exception (not the documented
`PaymentFailedException`) partway through `placeOrder`?**
`OrderService.placeOrder` only catches/handles the documented exception type in its rollback
logic; an unexpected unchecked exception would propagate straight up, uncaught, to the caller —
no cart is cleared, no order is persisted, which is actually the *safe* failure mode here (the
operation fails atomically rather than leaving a half-committed order), but it does mean the
customer sees a generic failure rather than a clean, specific error message. In production, I'd
wrap the whole method body with a catch-all that logs the unexpected exception with full
context and still surfaces a generic "something went wrong, your card was not charged" (verified
by checking payment status, not assumed) message, rather than leaking a raw stack trace.

**39. If a delivery partner's app crashes/loses connectivity mid-delivery (after `pickUp()` but
before `deliver()`), how does this system detect and recover?**
Honestly: it doesn't, today — there's no heartbeat/liveness mechanism for delivery partners in
this codebase, so a stuck `PICKED_UP` order would simply sit there indefinitely with no
automatic detection. This is a real, named gap (README §13 lists it), and the realistic
production fix is a periodic liveness check (partner app pings a location/status endpoint; if a
partner goes silent for N minutes while attached to an active order, that triggers a
support/ops alert and potentially a manual reassignment flow) that is out of scope for this
interview-level design but should be named as a known gap, not silently ignored.

**40. What's the blast radius if `RestaurantDashboardObserver.onOrderUpdated` throws an
exception?**
As currently written, `Order.notifyObservers` iterates observers and calls each directly with no
try/catch around individual calls — so yes, one misbehaving observer throwing would propagate
and potentially prevent *subsequent* observers in the list from being notified, and (depending on
where `notifyObservers` is called from relative to the `synchronized` block) could even cause
the triggering transition method itself to appear to throw, even though the state transition
itself already succeeded. This is a real robustness gap worth naming: each observer call should
be individually try/caught-and-logged so one broken notification channel can never affect
another, or the transition's own apparent success/failure.

---

## Production Architecture

**41. In a microservice decomposition of this system, where would you draw the service
boundaries?**
Likely four: a **Catalog service** (restaurants/menus/availability), an **Ordering service**
(cart, order lifecycle, the state machine — the most complex and most carefully-owned boundary),
a **Dispatch service** (delivery-partner registry, assignment, location tracking), and a
**Payments service** (charge/refund, likely wrapping a real external gateway). `Order` as
modeled here would live in Ordering; `DeliveryPartner`/assignment would move to Dispatch, with
Ordering calling Dispatch over an API/event rather than in-process method calls — meaning the
current `synchronized`-on-`Order`-monitor concurrency story would need to become a distributed
one (the DB optimistic-locking approach from Q31) the moment that boundary is drawn.

**42. Would you make delivery-partner assignment synchronous or event-driven in a real
microservice deployment, and why?**
Event-driven: Ordering publishes an "OrderAccepted" event; Dispatch consumes it, performs
assignment, and publishes "PartnerAssigned" (or "AssignmentFailed, retrying") back; Ordering
updates its own order record on receipt. This decouples the two services' availability/latency
(Dispatch being briefly slow or down doesn't block the customer-facing accept API call) at the
cost of eventual, not immediate, consistency between "order accepted" and "partner assigned" —
an acceptable trade here since the in-process version already treats assignment as
asynchronous/best-effort relative to acceptance.

**43. What single piece of operational tooling would most reduce your on-call burden for this
system, if you could only add one?**
A dashboard/alert on "orders stuck in a non-terminal state for longer than X minutes," bucketed
by status — this single signal would catch the acceptance-timeout-not-firing bug, the stuck-
PICKED_UP scenario from Q39, and any future stuck-state bug class all at once, without needing
bespoke detection logic per failure mode, because it's a generic invariant ("every order should
reach a terminal state within a bounded time") rather than a symptom-specific check.
