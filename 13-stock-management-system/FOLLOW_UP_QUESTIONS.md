# Follow-Up Questions — Stock Management System

Realistic interviewer follow-ups, categorized, each with a full answer. No question
repeats across categories.

---

## Object-Oriented Design

**1. Why is `InventoryKey` a separate class instead of just using a `String`
concatenation like `"SKU-1:WH-1"` as a map key?**
A dedicated value object gives compile-time type safety (you cannot accidentally
pass a warehouse code where a SKU code is expected), a correct `equals`/`hashCode`
contract independent of string-formatting choices (no risk of `"A:B"` vs `"A::B"`
inconsistencies), and a natural place to add behavior later (e.g. `of(Sku,
Warehouse)` factory method). String concatenation also risks collisions if either
component could ever contain the delimiter character.

**2. Why does `Lot` break the "value objects are immutable" rule used everywhere
else in this codebase?**
`Lot` represents one physical batch whose remaining quantity genuinely changes over
its lifetime as units are picked from it — that mutability is the actual business
reality, not an implementation shortcut. It's explicitly documented as the one
deliberate exception, and its mutation is tightly confined (only `deduct()`
changes state, only called while holding `LotManager`'s per-key lock), so it
doesn't reintroduce the uncontrolled shared-mutable-state problem the rest of the
immutability discipline exists to prevent.

**3. Why does `InventoryService` take its collaborators via constructor injection
rather than constructing them itself?**
Dependency Inversion: `InventoryService` depends on the `SkuRepository`/
`WarehouseRepository`/`InventoryLedger`/`AuditTrail` *interfaces*, not concrete
classes, and the composition root (`Main`, or test setup) decides which concrete
implementation to wire in. This is what makes swapping ledger strategies, or
swapping to DB-backed repositories, a zero-change operation for the service class,
and what makes the service trivially testable in isolation with fakes/in-memory
implementations.

**4. Why is `MovementType` an enum rather than a class hierarchy of
`StockInMovement`, `ReserveMovement`, etc.?**
There is no behavioral difference between movement types — they are pure
classification tags attached to an otherwise-identical `StockMovement` record. A
class hierarchy would add indirection with no polymorphic behavior to justify it
(no movement type overrides any method); an enum is the simplest correct
representation of "one of a fixed, closed set of kinds."

**5. Why does `ReorderRequest` exist as its own class instead of just logging a
string?**
It's a genuine domain concept (a replenishment suggestion) that callers may want to
inspect programmatically (e.g. a test asserting `getSuggestedQuantity()`, or a
future integration forwarding it to a purchasing system) — collapsing it to a log
string would destroy that structure and force downstream consumers to re-parse
text, which is strictly worse than carrying a typed object.

---

## SOLID Principles

**6. Where exactly does this design apply the Single Responsibility Principle,
concretely (not just "each class does one thing")?**
`InventoryLedger` implementations are responsible *only* for atomic counter
mutation and the overselling invariant — they know nothing about audit trails,
alerts, or catalogs. `AuditTrail` is responsible only for append/read of movement
history. `InventoryService` is responsible only for *orchestrating* those
collaborators in the right order — it contains no locking logic and no alerting
logic itself. Each has exactly one reason to change: the ledger changes only if the
concurrency algorithm changes; the service changes only if the orchestration
sequence changes.

**7. How does the Open/Closed Principle show up when adding a new
`StockEventListener`?**
Adding a third listener (e.g. a metrics exporter) requires writing one new class
implementing `StockEventListener` and calling `inventoryService.addListener(...)` —
zero changes to `InventoryService`, `InventoryLedger`, or any existing listener.
The service is closed for modification (its `recordAndNotify` loop already iterates
"whatever listeners exist") but open for extension via the interface.

**8. Does the Liskov Substitution Principle actually hold across the three
`InventoryLedger` implementations — can you swap any one for any other with no
observable behavioral difference to a correct caller?**
Yes for the documented contract: all three produce the same observable state
transitions (same final `available`/`reserved` values for the same sequence of
calls) and throw the same exception types under the same conditions
(`InsufficientStockException` on overselling attempts, `InvalidQuantityException`
on non-positive quantities). The one honest caveat (see Follow-up 15 under
"Design Patterns" and deep-dive Q15) is that `version` is not meaningfully
populated by the atomic-CAS strategy — a caller that specifically depends on
`version` semantics would observe a difference, which is why this is documented
rather than silently accepted as "fully substitutable with zero caveats."

**9. Where would the Interface Segregation Principle be violated if
`InventoryLedger` also included lot/batch allocation methods?**
Callers that only need aggregate reserve/release/fulfill (the vast majority) would
be forced to depend on — and any new ledger implementation forced to implement —
lot-allocation methods they don't need. This is exactly why
`LotAllocationStrategy`/`LotManager` are a separate interface and class entirely,
not methods bolted onto `InventoryLedger`.

**10. How does Dependency Inversion specifically enable the "swap to a real
database" story in README section 17?**
Because `InventoryService` depends on `InventoryLedger`/`SkuRepository`/
`WarehouseRepository`/`AuditTrail` abstractions (interfaces) rather than their
concrete in-memory implementations, a new class like
`JdbcInventoryLedger implements InventoryLedger` can be substituted at the
composition root with no change to `InventoryService`'s source at all — the
high-level orchestration policy doesn't depend on the low-level storage detail.

---

## Design Patterns

**11. Why Strategy and not a simple `if/else` on a "mode" enum inside one ledger
class?**
An `if/else` would make `InventoryLedger` a single class that knows about three
different concurrency mechanisms simultaneously, violating SRP, making unit tests
for one strategy inadvertently couple to the others' code paths, and making it
impossible to run the *same* test scenario against each (which is exactly what
`AbstractInventoryLedgerConcurrencyTest` does) without threading a mode parameter
through every test.

**12. Why is `InventoryService` a Facade and not a Mediator?**
A Mediator coordinates communication *between* a set of peer objects that would
otherwise need to know about each other directly (e.g. GUI widgets). Here, the
collaborators (`InventoryLedger`, `AuditTrail`, repositories, listeners) never need
to talk to each other at all — `InventoryService` is the only thing that calls any
of them, and they have no awareness of each other's existence. That's a Facade
(simplifying a complex subsystem's interface for external callers), not a
Mediator (decoupling peers that would otherwise be tightly coupled to each other).

**13. If you added retry/backoff behavior around any `InventoryLedger`
implementation, what pattern would that be, and why wasn't it built?**
That would be a Decorator (`RetryingInventoryLedger implements InventoryLedger`,
wrapping another `InventoryLedger` and adding retry/backoff/metrics around its
calls without changing the wrapped strategy's code). It wasn't built because there
is currently exactly one cross-cutting concern candidate and no concrete
requirement driving it — adding it now would be speculative; the Strategy boundary
at `InventoryLedger` is exactly where it would slot in later with zero disruption.

**14. Why didn't you use the Builder pattern for constructing `Sku` or
`Warehouse`?**
Both have exactly three required, non-optional, non-ambiguous-order constructor
parameters with no optional fields and no risk of parameter-order confusion given
their distinct types (all `String`, admittedly — a real risk for Builder to solve,
but with only three fields and clear naming at call sites, a Builder adds
boilerplate without solving a real problem here). Builder earns its keep with many
optional parameters or complex multi-step construction, neither of which applies.

**15. The three ledger strategies share no common superclass — only
`LedgerValidation`, a stateless helper. Why not an abstract base class with
template methods?**
A Template Method base class would pull in shared *state* (e.g. a shared map
structure) or force a common method-call skeleton across three implementations
that genuinely differ in their internal row representation (`AtomicReference` vs.
`ReentrantLock`-guarded fields vs. packed `AtomicLong`) — there is no common
algorithm skeleton to template, only a few lines of pure, stateless validation
logic, which is exactly what a package-private static helper class captures without
forcing an inheritance relationship that implies more shared structure than
actually exists.

---

## Data Structures

**16. Why `ConcurrentHashMap` and not `Hashtable` for the repositories and
ledgers?**
`Hashtable` synchronizes every method on one lock covering the *entire* table,
serializing all access across all keys — directly violating the per-key-isolation
NFR (section 2). `ConcurrentHashMap` achieves thread safety with much finer
internal locking (historically per-segment, now per-bin in modern JDKs) and
lock-free reads, giving genuinely independent throughput across different keys.

**17. Why `CopyOnWriteArrayList` for audit history instead of a plain
`synchronized` wrapped `ArrayList`?**
A synchronized `ArrayList` still requires the *caller* to synchronize externally
during iteration (iterating while another thread appends throws
`ConcurrentModificationException` even with `Collections.synchronizedList`, unless
you manually synchronize the iteration block too — an easy-to-forget bug source).
`CopyOnWriteArrayList` provides a consistent, un-interruptible snapshot to any
in-progress reader with zero caller-side synchronization, at the cost of an O(n)
copy per write — the right trade for read-heavy, append-occasional audit access.

**18. What's the time complexity of `FefoLotAllocationStrategy.allocate`, and
could it be improved?**
It's `O(n log n)` for the sort by expiry date plus `O(n)` for the allocation
sweep, where `n` is the number of lots for that key. It could be improved to
amortized `O(log n)` per allocation by maintaining a persistent min-heap by expiry
date across calls instead of re-sorting the full list every time — rejected here
because the expected `n` (lots per SKU/warehouse) is small (tens, not millions),
making the simpler sort both correct and more than fast enough, and a heap would
add state-management complexity (keeping the heap in sync as lots are received
between allocations) disproportionate to the actual access pattern.

**19. Why does `OptimisticVersionedInventoryLedger` allocate a new
`InventoryLevel` object on every retry attempt, even failed ones — isn't that
wasteful?**
Yes, it's a real, acknowledged cost (documented explicitly in the class-level
Javadoc and in README section 11's cost-model comparison) — each attempt computes
a brand-new immutable candidate object before attempting the CAS, and a failed CAS
discards that object entirely. This is precisely why the atomic-CAS strategy
(no allocation, pure primitive-long CAS) is cheaper per-operation and is the
right choice for the single hottest keys; the optimistic strategy trades a bit of
allocation overhead for an API that can trivially absorb new fields in the
snapshot object, which the packed-primitive approach cannot (see deep-dive Q15/16).

**20. Why use a `Function<InventoryLevel, InventoryLevel>` lambda parameter in
`OptimisticVersionedInventoryLedger.mutate` instead of five separate hand-written
retry loops?**
The five operations (`stockIn`, `reserve`, `releaseReservation`,
`fulfillReservation`, `adjust`) all share the exact same retry-until-CAS-succeeds
control flow and differ only in *what* new `InventoryLevel` to compute from the
current one. Extracting that identical control flow into one `mutate` helper
avoids duplicating (and risking divergent bugs in) the retry loop five times —
each business method becomes a small, readable, self-contained validation+
computation function passed into one correctly-written loop.

---

## Java Internals

**21. What does `records` buy you in `LotAllocationStrategy.LotAllocation`, and
why wasn't a full class used instead?**
`record LotAllocation(Lot lot, int quantityTaken)` gets a correct, concise
`equals`/`hashCode`/`toString` and immutable fields for free, for a type that is
purely a transparent carrier of two values with no behavior beyond accessors —
exactly the use case Java records were introduced for. A full class would be
strictly more boilerplate for identical semantics.

**22. Why does `AtomicCasInventoryLedger.pack` use `(reserved & 0xFFFFFFFFL)`
instead of just casting `reserved` to `long`?**
A plain `(long) reserved` performs *sign-extension* — if `reserved` were ever
negative (it shouldn't be, by invariant, but defensively), casting a negative `int`
directly to `long` would fill the upper 32 bits with `1`s, corrupting the packed
`available` bits when OR'd together. Masking with `0xFFFFFFFFL` first
zero-extends, guaranteeing only the low 32 bits are populated regardless of the
int's sign bit, before the two halves are combined.

**23. Why is `unpackAvailable` implemented as `(int) (packed >>> 32)` using the
unsigned right shift operator, specifically, and not `>>`?**
`>>` (signed shift) would replicate the sign bit of the packed `long` into the
vacated high bits during the shift, which is irrelevant here since we're shifting
the *entire* 64-bit value right by 32 to expose the high 32 bits as the new low 32
— but using `>>>` (unsigned shift) is the textually self-documenting choice that
this is bit-manipulation on a treated-as-unsigned-pattern value, not arithmetic on a
signed number, making the intent unambiguous to a future reader even though `>>`
would produce an identical result for this specific 32-then-cast pattern.

**24. Why does `Sku.equals` check `instanceof Sku sku` (pattern-matching
instanceof) rather than `getClass() != o.getClass()`?**
`Sku` is declared `final`, so there is no subclassing concern that `getClass()`
checks are normally defensive against (preventing a non-symmetric equals between a
base class and an untrusted subclass). With a final class, `instanceof` pattern
matching is both correct and more concise, and the pattern-variable binding
(`sku`) avoids a separate explicit cast.

**25. What Java Memory Model guarantee specifically makes the pessimistic
ledger's plain (non-volatile) `int` fields safe to read/write?**
The JMM's **lock happens-before** rule: an unlock of a given monitor/lock
happens-before every subsequent lock of that same monitor/lock by any thread.
Since every read and write of `Row.available`/`reserved`/`version` happens
strictly between that row's `lock()` and `unlock()` calls, any thread that
successfully acquires the lock is guaranteed to see every write made by the
previous lock holder — without needing `volatile`, because the lock itself is the
synchronization mechanism establishing visibility, exactly as a `synchronized`
block would.

---

## Multithreading

**26. Why `ReentrantLock` instead of a `synchronized` block in
`PessimisticLockInventoryLedger`?**
Functionally near-identical for this use case (both provide mutual exclusion and
lock happens-before visibility). `ReentrantLock` was chosen for the explicit,
visible `lock()`/`unlock()` pairing in code (clearer to narrate and read in an
interview/documentation context than an implicit `synchronized` block boundary),
and because it leaves room to add `tryLock(timeout)` later without restructuring
the method — `synchronized` offers no timeout option at all.

**27. Could two threads deadlock inside a single `PessimisticLockInventoryLedger`
operation? Why or why not?**
No — every single method in that class acquires exactly one `Row`'s lock, performs
its work, and releases it in a `finally` block before returning. A deadlock
requires at least two threads each holding a lock the other needs; with at most
one lock held per thread at any time within this class, that precondition can
never arise (see deep-dive Q17 for when a future multi-key operation would change
this).

**28. What does `@Timeout(30)` on the concurrency tests actually protect
against?**
It bounds worst-case test-suite hang time if a bug introduced a real deadlock or
livelock (e.g. an infinite CAS retry loop that somehow never terminates) — without
it, a broken implementation could hang the test run indefinitely rather than
failing fast with a clear timeout signal, which matters especially in CI where a
hung test consumes a runner slot silently.

**29. Why does the concurrency test use a `CountDownLatch` to start all threads,
rather than just submitting tasks to an executor and letting them run as
scheduled?**
Without the latch, thread start times would be staggered by scheduling overhead,
meaning most of the contention the test is trying to provoke (many threads hitting
the same key in the same instant) might never actually materialize — the test
could pass even with a buggy, non-atomic implementation purely by accident of
timing. The latch maximizes the probability that all 50 threads genuinely overlap
on the same key, making the test a meaningful stress test rather than a
coincidental pass.

**30. Is `CopyOnWriteArrayList` in `ReorderAutomationListener.raisedRequests` safe
under concurrent `onStockEvent` calls, and is its `add` atomic with the
`putIfAbsent` debounce check right before it?**
`CopyOnWriteArrayList.add` is itself thread-safe (internally synchronized). The
*combination* of `putIfAbsent` (on the debounce map) followed by `.add()` (on the
list) is not a single atomic unit across both data structures — but it doesn't
need to be: the `putIfAbsent` is what decides "do I get to add a request at all"
(exactly one thread wins that race per key, deterministically), and only the
winning thread proceeds to `.add()`, so there's no risk of two threads both adding
a duplicate request for the same below-threshold episode, even though the two
operations aren't wrapped in one lock.

---

## Database and Persistence

**31. If you moved `AuditTrail` to a real database table, what index would you
add first, and why that one specifically?**
A composite index on `(sku_code, warehouse_code, occurred_at)` — exactly matching
the access pattern of `history(InventoryKey)` (filter by key, implicitly ordered
by time for reconstructing history) and `all()` (which currently sorts by
`occurredAt` in application code; with the right index, that sort could be pushed
down to the database instead).

**32. Why would the optimistic strategy's SQL mapping need the application to
check "affected row count," and what happens if that check is skipped?**
`UPDATE ... WHERE version = ?` can affect zero rows if another transaction won
the race and updated the version first — this is the SQL-level equivalent of a
failed CAS. If the application blindly assumes the update succeeded without
checking affected-row-count, it would silently proceed as if the reservation
succeeded even though the database made no change at all — a correctness bug far
worse than the original race, because it fails *silently* rather than throwing.

**33. Does the pessimistic strategy's `SELECT ... FOR UPDATE` SQL mapping need a
specific isolation level, or does the default usually suffice?**
It needs at least `READ COMMITTED` combined with `FOR UPDATE`'s row-lock semantics
(most databases support this at the default isolation level) — the row lock itself
is what prevents concurrent modification, not the isolation level per se. However,
`REPEATABLE READ` or stricter is advisable if the transaction performs more than
one read of the same row and needs those reads to agree (e.g. reading available
stock, doing other work, then re-validating) — under plain `READ COMMITTED`,
a second read within the same transaction could see a different value if the lock
were somehow released and reacquired, though with `FOR UPDATE` held continuously
for the transaction's duration this specific risk is moot for this use case.

**34. How would you detect, after the fact, that `available` went negative due to
a bug (not prevented at write time) — what query would you run?**
`SELECT * FROM inventory_level WHERE available < 0;` — trivial given the `CHECK
(available >= 0)` constraint in the schema (README section 12) would actually
*prevent* this from ever being persisted in the first place (the database itself
enforces the invariant as a second line of defense beyond the application-level
ledger logic) — a concrete example of defense-in-depth between the application and
schema layers.

**35. The in-memory audit trail has unbounded growth. What's the first production
decision you'd need to make about it, concretely?**
A retention policy decision: how long does a `StockMovement` need to stay queryable
in the "hot" operational store versus being archived to cold storage (e.g. a data
lake/warehouse for historical analytics)? This directly trades off query
performance (smaller hot table = faster recent-history lookups) against
compliance/audit requirements (some industries require years of retained stock
movement history) — a business decision, not a purely technical one, that the
engineering design must simply make room for (e.g. a scheduled archival job, a
partitioned table by `occurred_at` month).

---

## Performance

**36. Why is `reserve()` specifically called out as "the hottest operation," and
not `stockIn()`?**
`stockIn` typically happens at a much lower frequency and lower concurrency
(goods arrive in batches, e.g. once per truck delivery) than `reserve`, which
fires once per concurrent customer checkout — for a popular SKU, potentially
thousands of simultaneous reservation attempts during a flash sale, versus a
handful of stock-in events per day. The concurrency strategy comparison is
explicitly framed around `reserve` because that's where real-world contention
actually concentrates.

**37. What would you measure to decide, empirically, which of the three
strategies to deploy for a given SKU?**
Per-key operation throughput and p99 latency under the SKU's actual observed
concurrent-request rate, plus the `ConcurrencyRetryExhaustedException` rate
specifically for the optimistic strategy (a direct signal that contention exceeds
what retries can absorb) — these three numbers, gathered from production
monitoring (section 17), would tell you empirically whether a given SKU's
read/write pattern favors optimistic (low retry rate = good fit), pessimistic
(stable but possibly higher baseline latency under load), or atomic-CAS (lowest
overhead, worth it only if allocation/GC pressure from the optimistic strategy is
itself a measured problem).

**38. Is there a straightforward way to reduce GC pressure from the optimistic
strategy without switching strategies entirely?**
Not meaningfully, no — the strategy's core correctness mechanism *is* creating
and CASing a new immutable object per attempt; removing that allocation would
require abandoning the immutable-snapshot-plus-CAS design for something closer to
the atomic-CAS strategy's packed-primitive approach, at which point you've
essentially switched strategies. This is an honest "no free lunch" answer rather
than inventing a fictitious micro-optimization.

---

## Failure Handling

**39. If `InsufficientStockException` and `ConcurrencyRetryExhaustedException`
were accidentally caught by the same broad `catch (RuntimeException e)` block
upstream and treated identically (e.g. both just returned "out of stock" to the
user), what real-world harm would that cause?**
It would mask a system-health problem as a normal business outcome: genuine retry
exhaustion (pathological contention, possibly indicating a bug or an unexpectedly
hot key that needs a different strategy) would look, from a dashboard's
perspective, like completely normal "customers tried to buy something we didn't
have" — meaning on-call engineers would never get paged for a condition they
actually need to investigate, while the business might incorrectly conclude
customer demand exceeds supply when the real problem is concurrency-control
tuning.

**40. What happens, concretely, if `InventoryLedger.initialize()` is called twice
for the same key with different `initialAvailable` values — is that a silent
bug?**
Looking at all three implementations: each uses `putIfAbsent`-style semantics
(`rows.putIfAbsent(...)` for optimistic/CAS, same pattern for pessimistic) — the
*second* call is silently a no-op; the key keeps its original opening value. This
is intentional idempotency (calling `initialize` again shouldn't reset a key that
already has real transactional history against it) but is worth stating plainly
as "second call is ignored," not assuming a reader infers that from the method
name alone.

---

## Production Architecture

**41. If this service needed a REST API layer on top, where would request/
response DTOs live, and would they duplicate the domain model?**
They would live in a new, separate adapter layer (e.g.
`lld.stockmanagement.api.dto`), translating between HTTP JSON payloads and the
existing domain calls (`InventoryService.reserveForOrder(String, String, int,
String)` etc.) — not duplicating domain logic, just shape-translating at the
boundary. This keeps the domain model (section 8's explicit choice not to wrap
primitives in DTOs internally) decoupled from any specific transport concern,
exactly the boundary a real production system would draw.

**42. How would you roll out a change from the pessimistic strategy to the
optimistic strategy for a live, already-running warehouse system with zero
downtime?**
Since `InventoryLedger` is chosen once at composition-root wiring time and both
implementations share the exact same external contract (same interface, same
exception types, same invariant guarantees), the actual code swap is a
single-line change at the wiring point — the operational risk is entirely in *how*
you validate the new strategy under real production contention before fully
committing (e.g. shadow-running the new strategy against a canary subset of
warehouses/SKUs first, comparing `ConcurrencyRetryExhaustedException` rates and
latency, before a full cutover) — the design enables an easy code change; safe
rollout is a deployment-process concern layered on top of that.

**43. What's the minimum viable observability you'd add before trusting this in
production, beyond what's already documented in section 17?**
A dashboard tracking, per strategy and per SKU-hotness tier: reservation
success/failure rate, p50/p99 latency, and (for the optimistic strategy
specifically) retry-count distribution per successful operation — not just
pass/fail, since a reservation that succeeded only after 400 retries is a very
different signal from one that succeeded on the first attempt, even though both
show up as "success" in a naive success-rate metric.
