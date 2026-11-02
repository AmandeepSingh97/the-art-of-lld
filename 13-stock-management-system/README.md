# Stock Management System (Warehouse Inventory)

A Staff-level low-level design (LLD) solution for a warehouse inventory / stock
management system: products (SKUs), multi-warehouse stock levels, reservations for
pending orders, low-stock alerting, reorder-point automation, a full audit trail, and
— the centerpiece of this problem — three directly-compared concurrency strategies
that all guarantee **no overselling** under concurrent decrement of the same SKU.

> Scope note: this is warehouse/retail **inventory management**, not stock-market
> trading. "Stock" here means physical goods on a shelf, not equities.

---

## 1. Problem Understanding

**Plain explanation.** A retailer or 3PL (third-party logistics) operator stores
products across one or more warehouses. Each product variant is identified by a SKU
(Stock Keeping Unit). For every (SKU, warehouse) pair the system tracks how many
units are physically present (`available`) and how many are already promised to a
pending order but not yet shipped (`reserved`). Goods arrive (stock-in), get promised
to orders (reserve), get shipped (fulfill) or cancelled (release), and occasionally
get manually corrected (cycle-count adjustment). Every one of those events must be
recorded so the business can reconstruct "how did we get to this number" at any time
(audit trail), and operations staff must be proactively told when a SKU is running
low (low-stock alert) or should be reordered from a supplier (reorder-point
automation).

**Real-world use case.** This is the core of any e-commerce or warehouse management
system (WMS): Amazon FBA, Shopify's inventory module, SAP EWM, and any retailer's
backend all solve exactly this problem at their core, differing mainly in scale and
the sophistication of their reorder forecasting.

**What the interviewer evaluates.**
- Can you correctly distinguish `available` vs `reserved` vs `onHand`, and get the
  state machine between them right (this is the most common bug surface)?
- Can you reason rigorously about concurrent decrements of a shared counter and
  compare at least two locking strategies with real trade-offs, not just name-drop
  "use a lock"?
- Do you design an audit trail as a first-class, append-only concept rather than an
  afterthought?
- Do you avoid overengineering (e.g. don't reach for a distributed lock service or a
  message queue for a single-process in-memory exercise) while still producing
  genuinely thread-safe code?
- Do your class responsibilities stay single-purpose (SRP) rather than collapsing
  into one "InventoryManager god class"?

**In scope.**
- SKU and warehouse catalog (minimal, in-memory).
- Per-(SKU, warehouse) available/reserved inventory levels.
- Stock-in, stock-out (via adjustment), reserve, release-reservation, fulfill-order.
- Concurrency-safe decrement with three interchangeable strategies.
- Append-only audit trail of every movement.
- Low-stock alerting (Observer pattern).
- Reorder-point automation (Observer pattern), producing a `ReorderRequest` value
  object (not an actual purchase order integration).
- Optional extension: batch/lot tracking with FEFO (First-Expired-First-Out)
  allocation and expiry handling.

**Out of scope** (explicitly, and why).
- **Persistent storage / real database.** This is an in-memory LLD exercise; see
  section 12 for why a DB is unnecessary here and how the design maps onto one.
- **Distributed locking / multi-node coordination.** Single-process scope; see
  section 14 for how this would extend to multiple service instances.
- **Demand forecasting for reorder points.** `ReorderPolicy` uses a static
  min/reorder-quantity policy; real systems compute this from historical demand and
  supplier lead time, which is a separate forecasting system, not an LLD concern.
- **Purchase order lifecycle / supplier integration.** `ReorderRequest` is a
  suggestion value object; actually creating, sending, and tracking a PO against a
  supplier is a separate bounded context.
- **Pricing, taxation, returns, or shipment tracking.** Separate domains.
- **Authentication/authorization.** Out of scope for an LLD round.

---

## 2. Requirement Gathering

### Functional — essential
- Register a SKU and a warehouse (minimal catalog).
- Initialize an opening inventory level for a (SKU, warehouse) pair.
- Stock-in: increase `available` when goods are received.
- Reserve: atomically move quantity from `available` to `reserved` for a pending
  order; must fail cleanly (no partial effect) if insufficient stock.
- Release a reservation: return reserved quantity to `available` (order cancelled).
- Fulfill a reservation: permanently remove reserved quantity (order shipped).
- Query current inventory level for a (SKU, warehouse).
- Record every mutation in an audit trail with who/what/when/resulting-state.

### Functional — optional (implemented as extensions, clearly separated)
- Manual stock adjustment (cycle counts), independent of order reservations.
- Low-stock alerting when `available` crosses a threshold.
- Reorder-point automation producing replenishment suggestions.
- Batch/lot tracking with expiry and FEFO allocation.

### Non-functional
- **Performance.** Reservation (the hottest, most contended operation) must be O(1)
  amortized per call and must not require a global lock across unrelated SKUs/
  warehouses — contention on one SKU must never slow down operations on another.
- **Scalability.** In-memory, single-process scale target: thousands of SKUs ×
  warehouses, with a handful of "hot" SKUs seeing high concurrent reservation
  traffic (e.g. a flash sale). See section 14 for the explicit single-process vs
  distributed boundary.
- **Availability.** No single operation should be able to block the whole system;
  per-key locking/CAS (not a global lock) is a hard requirement, not a nicety.
- **Consistency.** Strong consistency for the available/reserved invariant within a
  single process: `available` must never go negative, and at all times
  `onHand = available + reserved` exactly reflects all committed movements. This is
  linearizable per-key, not globally serializable (cross-key atomicity is explicitly
  not required — see section 16).
- **Extensibility.** Adding a new movement type (e.g. `STOCK_OUT` for shrinkage) or
  a new listener (e.g. a metrics exporter) must not require touching existing ledger
  or repository code (Open/Closed via the Strategy and Observer interfaces).
- **Reliability.** A failed/rejected mutation (e.g. insufficient stock) must leave
  the system in exactly the state it was in before the call — no partial writes, no
  phantom audit entries.
- **Thread-safety.** Every shared mutable data structure must have an explicit,
  justified concurrency strategy — "assume single-threaded" is not acceptable even
  though the demo (`Main`) happens to run single-threaded; the concurrency tests
  prove multi-threaded correctness directly.

### Interview-necessary vs production-extension split
| Interview-necessary (this repo implements fully) | Production extension (documented, not built) |
|---|---|
| Per-key concurrency-safe reserve/release/fulfill | Multi-node distributed locking (Redis/Zookeeper) |
| In-memory audit trail | Durable, queryable audit log (Kafka + OLAP store) |
| Static reorder policy | Demand-forecast-driven reorder points |
| Synchronous observer notification | Async event bus with retry/dead-letter handling |
| In-memory catalog repository | Real RDBMS-backed catalog with caching |

---

## 3. Clarifying Questions

These are the assumptions this implementation bakes in, and *why* a different answer
would change the design. In an actual interview, ask these before writing code.

1. **"Is `reserved` a real concept, or do orders decrement `available` directly?"**
   *Why it matters:* if orders decrement `available` directly with no reservation
   step, you only need one counter, not two, and the whole reserve/release/fulfill
   state machine collapses. *Assumption made:* reservations are real (this is how
   essentially every e-commerce checkout works — stock is held during checkout/
   payment, then either released on cancellation or fulfilled on shipment), so two
   counters and a 3-state transition are implemented.

2. **"Can a single warehouse have negative available stock ever, even temporarily
   (e.g. to support backorders)?"** *Why it matters:* if backorders are allowed,
   "no overselling" is not an invariant and the whole concurrency section changes
   shape (no CAS/lock needed to prevent negative values — you'd instead need to
   decide a different business rule for backorder thresholds). *Assumption made:*
   no backorders; `available` must never be negative — this is the hard invariant
   the concurrency comparison protects.

3. **"Is inventory partitioned per warehouse, or is there a single global pool?"**
   *Why it matters:* a single global pool needs cross-warehouse allocation logic
   (which warehouse ships this order?) which is a different, harder problem
   (allocation/sourcing). *Assumption made:* inventory is tracked per (SKU,
   warehouse) independently; cross-warehouse sourcing is out of scope.

4. **"Does the audit trail need to be queryable/paged at scale, or is 'list of
   events per key' sufficient?"** *Why it matters:* at real scale an audit trail
   needs pagination, retention policy, and probably a separate analytical store.
   *Assumption made:* in-memory, unbounded-growth list per key is acceptable for
   this exercise; section 12 and 17 describe the production evolution.

5. **"Should low-stock alerts and reorder requests be synchronous (block the
   calling thread) or asynchronous (fire-and-forget)?"** *Why it matters:* a slow
   listener on the synchronous path directly slows down every reservation; an async
   design needs a queue and must then also handle delivery failure/backpressure.
   *Assumption made:* synchronous, in-process notification (listeners are expected
   to be fast and side-effect-light); section 17 describes evolving to an async
   event bus.

6. **"Is a single reorder request enough, or must the system avoid flooding the
   purchasing team with duplicate suggestions while stock stays low?"** *Why it
   matters:* naive "alert every time below threshold" would raise a new reorder
   request on every single subsequent order while stock remains low. *Assumption
   made:* reorder requests are debounced per key until stock climbs back above the
   reorder point (see `ReorderAutomationListener`); low-stock *alerts*, by contrast,
   are intentionally **not** debounced the same way — see section 5, invariant 7,
   for why that asymmetry is deliberate.

7. **"Is batch/lot tracking (with expiry) required, or just aggregate counts?"**
   *Why it matters:* lot tracking adds a whole additional allocation-strategy
   dimension (FIFO vs FEFO) and per-lot mutable state. *Assumption made:* built as
   a clearly separated optional extension (`lld.stockmanagement.lot`) that does not
   entangle with the core ledger, since the prompt marks it optional.

8. **"What concurrency-control strategy does the interviewer want to see — do they
   want one implementation or a comparison?"** *Why it matters:* this determines
   how much time to spend on the ledger vs. the rest of the design. *Assumption
   made:* given the prompt explicitly calls out comparing all three (optimistic
   version column, pessimistic row lock, atomic CAS counters), all three are
   implemented behind one `InventoryLedger` interface (Strategy pattern) so they are
   directly comparable and swappable.

---

## 4. Domain Modelling

### Entities (have identity, not just values)
- **`InventoryService`** — the domain service / facade. Its identity is "the one
  service instance wired for a given deployment"; it owns no business state itself
  (it delegates all state to the ledger/repositories/audit trail), making it
  effectively stateless except for its listener registry.

### Value objects (immutable, identity = content)
- **`Sku`** — `skuCode`, `name`, `unitOfMeasure`. Equality by `skuCode`.
- **`Warehouse`** — `warehouseCode`, `name`, `region`. Equality by `warehouseCode`.
- **`InventoryKey`** — composite `(skuCode, warehouseCode)`; the concurrency
  partitioning key used throughout the ledger layer.
- **`InventoryLevel`** — immutable snapshot of `available`, `reserved`, `version`.
  Every ledger mutation method returns a fresh `InventoryLevel`; none are ever
  mutated after construction.
- **`ReorderPolicy`** — `reorderPoint`, `reorderQuantity`.
- **`StockMovement`** — one immutable audit record.
- **`StockEvent`** — one immutable observer-notification payload.
- **`ReorderRequest`** — one immutable replenishment suggestion.
- **`Lot`** (optional extension) — technically mutable (`quantity` decreases in
  place as units are picked), because it represents one physical pallet/batch whose
  remaining count is tracked over its lifetime; see its Javadoc for why this one
  exception to "value objects are immutable" is deliberate and safely confined.

### Enums
- **`MovementType`** — `STOCK_IN`, `STOCK_OUT`, `RESERVE`, `RELEASE_RESERVATION`,
  `FULFILL_RESERVATION`, `ADJUSTMENT`.

### Interfaces (abstractions the rest of the system depends on)
- **`InventoryLedger`** (Strategy) — the one interface with three interchangeable
  concurrency-control implementations.
- **`SkuRepository`** / **`WarehouseRepository`** (Repository) — catalog
  persistence abstraction.
- **`AuditTrail`** — append-only movement history abstraction.
- **`StockEventListener`** (Observer) — subscriber contract for stock events.
- **`LotAllocationStrategy`** (Strategy, optional extension) — which physical lot(s)
  satisfy a withdrawal.

### Domain services
- **`InventoryService`** — orchestrates catalog validation → ledger mutation →
  audit recording → observer notification, in that fixed order (see section 6,
  sequence diagrams).
- **`LotManager`** (optional extension) — orchestrates lot receipt and FEFO
  withdrawal, independent of the core ledger.

### Repositories
- **`InMemorySkuRepository`**, **`InMemoryWarehouseRepository`** — `ConcurrentHashMap`-
  backed implementations.

### Factories
**Deliberately not used.** `Sku`, `Warehouse`, `InventoryKey`, etc. have simple
constructors with straightforward validation; there is no polymorphic construction
decision (no "which subtype to build based on input") that would justify a Factory.
Forcing one in would be exactly the kind of "pattern for its own sake" this repo
avoids (see section 7 for patterns actually used and why).

### Strategies
- **`InventoryLedger`** implementations (`OptimisticVersionedInventoryLedger`,
  `PessimisticLockInventoryLedger`, `AtomicCasInventoryLedger`) — the central
  Strategy of this problem.
- **`LotAllocationStrategy`** implementation (`FefoLotAllocationStrategy`).

### Composition / association / inheritance, and ownership/lifecycle
- `InventoryService` **composes** one `SkuRepository`, one `WarehouseRepository`,
  one `InventoryLedger`, one `AuditTrail`, and **associates with many**
  `StockEventListener`s (added after construction, so this is association, not
  ownership — listeners can outlive or be shared across services in tests).
- `InventoryLedger` implementations **own** their internal per-key row objects
  (`AtomicReference<InventoryLevel>`, `Row`, or `AtomicLong`) — these never escape
  the ledger; callers only ever see immutable `InventoryLevel` snapshots.
- `StockMovement` and `StockEvent` are **created and owned transiently** by
  `InventoryService` per call; the audit trail takes ownership of `StockMovement`
  for its entire (unbounded, in-memory) lifetime once recorded.
- No inheritance hierarchies are used anywhere in the core domain model —
  composition and interface implementation only. The one abstract class in the
  codebase (`AbstractInventoryLedgerConcurrencyTest`) exists purely to share a test
  scenario across three implementations and is not part of the production domain
  model; see "Design Patterns" in section 7 for why inheritance was avoided
  elsewhere.

---

## 5. Domain Invariants

Business rules that must always hold, and exactly where they are enforced:

1. **`available >= 0` at all times.** Enforced inside every `InventoryLedger`
   mutation method (`reserve`, `adjust`) by checking the pre-mutation value before
   committing; this is the invariant all three concurrency strategies exist to
   protect under concurrent access (see section 11).
2. **`reserved >= 0` at all times.** Enforced by `InventoryLevel`'s constructor
   (throws `IllegalArgumentException` if violated) as a last-resort safety net, and
   by `fulfillReservation`/`releaseReservation` clamping logic in each ledger.
3. **`onHand = available + reserved` is conserved across `reserve` /
   `releaseReservation` / `fulfillReservation`.** `reserve` and `releaseReservation`
   move quantity between the two counters without changing their sum; only
   `stockIn`, `fulfillReservation`, and `adjust` change the sum (goods entering,
   leaving, or being corrected). Enforced by construction — every ledger mutation
   method only ever adds to one counter and subtracts from the other within the
   same atomic step (see `reserve`/`releaseReservation` bodies).
4. **A rejected mutation has zero side effects.** If `InventoryLedger.reserve`
   throws `InsufficientStockException`, the row is provably unchanged (optimistic:
   the failed validation happens before any CAS is attempted; pessimistic: the
   exception is thrown while still holding the lock, before any field write;
   atomic-CAS: validation happens against the freshly-read packed value before any
   CAS is attempted) **and** `InventoryService.recordAndNotify` never runs (it is
   only called with the ledger's return value, so an exception thrown by the ledger
   propagates past it — see `InventoryService` source).
5. **Every successful mutation produces exactly one audit record.** Enforced
   structurally: `InventoryService`'s six mutation methods each call exactly one
   ledger method followed by exactly one `recordAndNotify` call; there is no code
   path that mutates the ledger without also recording.
6. **SKU codes and warehouse codes are globally unique.** Enforced by
   `InMemorySkuRepository`/`InMemoryWarehouseRepository` using `putIfAbsent`
   (`SkuRepository.register` throws `DuplicateSkuException` on collision).
7. **Low-stock alerts fire on every event while below threshold; reorder requests
   fire at most once per below-threshold episode.** This asymmetry is intentional,
   not an oversight: alerts are meant to keep an operator informed/paged while a
   problem persists (silence while still low-stock would be a visibility gap), while
   duplicate purchase-order suggestions would be actively harmful (over-ordering).
   See `LowStockAlertListener` (no debouncing) vs. `ReorderAutomationListener`
   (explicit `requestAlreadyRaised` flag, cleared only when stock climbs back above
   the reorder point).
8. **(Optional extension) A lot allocation never takes more units from a lot than
   that lot currently holds, and never allocates from an expired lot.** Enforced in
   `FefoLotAllocationStrategy.allocate`, which filters expired lots before
   allocating and uses `Lot.deduct`, which clamps to the lot's current quantity.

---

## 6. Architecture and Class Diagram

See `diagrams/component-diagram.md` for the module-level component diagram,
`diagrams/class-diagram.md` for the full class diagram (matches `src/` exactly —
every class/interface/method shown there exists verbatim in the source), and
`diagrams/sequence-diagrams.md` for four key flows:
1. Reserve stock for an order (happy path, including observer fan-out).
2. Reserve stock with insufficient quantity (no overselling, no side effects).
3. Two threads concurrently reserving against the same key under the optimistic
   strategy (illustrates the CAS retry loop concretely).
4. Stock-in / replenishment clearing a reorder-automation flag.

---

## 7. Design Patterns

| Pattern | Why needed | Where applied | Alternative considered | Why rejected |
|---|---|---|---|---|
| **Strategy** | The entire point of this problem is to compare three different, swappable concurrency-control implementations behind one contract. | `InventoryLedger` interface + `OptimisticVersionedInventoryLedger` / `PessimisticLockInventoryLedger` / `AtomicCasInventoryLedger`. Also `LotAllocationStrategy` + `FefoLotAllocationStrategy`. | A single hard-coded locking approach with `if/else` branches for "mode". | Would violate Open/Closed (adding a fourth strategy means editing existing code) and make the three approaches harder to unit-test and compare in isolation — the explicit goal of this exercise. |
| **Observer** | Low-stock alerting and reorder automation are cross-cutting concerns that must react to every stock mutation without the ledger or service needing to know about them. | `StockEventListener` interface; `InventoryService` is the subject, notifying `LowStockAlertListener` and `ReorderAutomationListener` after each mutation. | Hard-code alert/reorder logic directly inside `InventoryService`. | Would violate SRP (service would own alerting + reorder policy logic) and Open/Closed (adding a third listener, e.g. a metrics exporter, would require editing the service). |
| **Repository** | Decouple `InventoryService` from how/where the catalog is stored. | `SkuRepository` / `WarehouseRepository` interfaces with in-memory implementations. | Let `InventoryService` hold `Map<String, Sku>` directly. | Works for this exercise but conflates persistence mechanism with orchestration logic, and makes swapping to a real DB-backed catalog (section 17) require touching the service instead of just swapping an implementation. |
| **Facade** | Give callers one coherent entry point instead of requiring them to coordinate ledger + audit + repositories + listeners themselves. | `InventoryService`. | Expose `InventoryLedger`, `AuditTrail`, and repositories directly to callers. | Would leak the "validate → mutate → audit → notify" sequencing responsibility to every caller, risking a caller that forgets to audit a mutation or notifies listeners before the mutation actually committed. |
| **Value Object** | Correctness of equality/identity for types used as map keys (`InventoryKey`) and for safe sharing across threads (`InventoryLevel`, `StockMovement`). | `Sku`, `Warehouse`, `InventoryKey`, `InventoryLevel`, `ReorderPolicy`, `StockMovement`, `StockEvent`, `ReorderRequest`. | Mutable POJOs with getters/setters. | Mutable shared state is exactly what the concurrency section is working hard to avoid; immutability here removes an entire class of races for free. |

**Patterns explicitly *not* used, and why:**
- **Singleton** — nothing in this domain needs exactly one instance enforced by the
  type itself; `InventoryService` is naturally a single instance per deployment
  because the composition root wires it that way, not because the class enforces
  it. Forcing a Singleton would make testing harder (can't construct isolated
  instances per test, as the test suite does).
- **Factory / Abstract Factory** — see section 4; no polymorphic construction
  decision exists that would justify one.
- **Command** — considered for the audit trail (each `StockMovement` could be
  modeled as an executable/undoable Command). Rejected because nothing in this
  domain ever needs to *replay* or *undo* a movement generically — `releaseReservation`
  and `fulfillReservation` are their own distinct operations, not "undo of reserve,"
  and audit records are read-only history, not executable objects. Command would add
  an execute/undo contract with no caller that ever calls `undo()`.
- **Decorator** — considered for composing multiple `InventoryLedger` behaviors
  (e.g. "add retry logic" or "add metrics" around any strategy). Rejected for this
  exercise's scope since there is exactly one cross-cutting concern per strategy and
  no need to stack several; noted in section 17 as a natural production evolution
  point (e.g. a `MeteredInventoryLedger` decorator).

---

## 8. API and Method Design

All public API lives in `lld.stockmanagement.service.InventoryService` (the facade)
plus the `lld.stockmanagement.ledger.InventoryLedger` strategy interface for anyone
operating below the facade (e.g. tests).

### `InventoryService` (facade — primary API surface)
```java
void registerSku(Sku sku);
void registerWarehouse(Warehouse warehouse);
void addListener(StockEventListener listener);

void initializeInventory(String skuCode, String warehouseCode, int openingQuantity);
InventoryLevel getLevel(String skuCode, String warehouseCode);

InventoryLevel stockIn(String skuCode, String warehouseCode, int quantity, String referenceId);
InventoryLevel reserveForOrder(String skuCode, String warehouseCode, int quantity, String orderId);
InventoryLevel releaseReservation(String skuCode, String warehouseCode, int quantity, String orderId);
InventoryLevel fulfillOrder(String skuCode, String warehouseCode, int quantity, String orderId);
InventoryLevel adjustStock(String skuCode, String warehouseCode, int delta, String reason);

List<StockMovement> auditHistory(String skuCode, String warehouseCode);
```

**Request/response objects.** Deliberately *not* wrapped in DTO request/response
classes — every parameter is either a primitive or an existing immutable domain
value object (`Sku`, `Warehouse`), and every return is the authoritative
`InventoryLevel` snapshot. Introducing request/response wrapper objects here would
add indirection with no behavior (classic over-engineering smell for an in-process
API); section 17 notes where a real request/response boundary (REST DTOs) belongs
if this became a networked service.

**Exceptions and who's responsible for what:**
| Exception | Thrown by | Meaning | Caller responsibility |
|---|---|---|---|
| `SkuNotFoundException` | `InventoryService.resolveKey` | Referenced SKU not registered | Validate SKU exists before calling, or catch and surface "unknown product" |
| `WarehouseNotFoundException` | `InventoryService.resolveKey` | Referenced warehouse not registered | Same as above for warehouse |
| `InsufficientStockException` | `InventoryLedger.reserve` / `.fulfillReservation` / `.adjust` | Expected business outcome, not a bug | Catch and surface "out of stock" / "cannot fulfill" to the user; do not retry blindly |
| `InvalidQuantityException` | `LedgerValidation.requirePositive` | Caller passed zero/negative quantity | Fix caller input; this indicates a caller bug |
| `DuplicateSkuException` | `SkuRepository.register` | SKU code collision | Caller must use a different code or treat as idempotent-registration-failed |
| `ConcurrencyRetryExhaustedException` | `OptimisticVersionedInventoryLedger` only | Pathological contention, not a business outcome | Should be logged as a system-health signal (alerting/paging), distinct from `InsufficientStockException` |

**Validation responsibilities.** `InventoryService` validates referential integrity
(SKU/warehouse exist) before delegating to the ledger. The ledger validates quantity
positivity and the overselling invariant. Value object constructors (`Sku`,
`Warehouse`, `InventoryLevel`, `ReorderPolicy`) validate their own field invariants
(non-blank codes, non-negative counts) so an invalid object can never be constructed
in the first place — validation is pushed as close to construction as possible
rather than centralized in one "validator" god class.

### `InventoryLedger` (strategy interface — see section 9 for full code)
```java
void initialize(InventoryKey key, int initialAvailable);
InventoryLevel snapshot(InventoryKey key);
InventoryLevel stockIn(InventoryKey key, int quantity);
InventoryLevel reserve(InventoryKey key, int quantity);
InventoryLevel releaseReservation(InventoryKey key, int quantity);
InventoryLevel fulfillReservation(InventoryKey key, int quantity);
InventoryLevel adjust(InventoryKey key, int delta);
```

---

## 9. Core Implementation

Full compiling Java code lives under `src/main/java/lld/stockmanagement/`. The
components most important to this problem, referenced directly (not reproduced as
pseudocode — read the real files):

- **`model/InventoryLevel.java`** — the immutable snapshot every strategy returns.
- **`ledger/InventoryLedger.java`** — the Strategy contract.
- **`ledger/OptimisticVersionedInventoryLedger.java`** — version-column / CAS-on-
  immutable-object strategy (full read-compute-CAS retry loop).
- **`ledger/PessimisticLockInventoryLedger.java`** — per-row `ReentrantLock`
  strategy (`SELECT ... FOR UPDATE` analogue).
- **`ledger/AtomicCasInventoryLedger.java`** — lock-free bit-packed `AtomicLong`
  strategy.
- **`service/InventoryService.java`** — the facade orchestrating all of the above.
- **`observer/LowStockAlertListener.java`** and **`observer/ReorderAutomationListener.java`**
  — the two Observer implementations.
- **`lot/FefoLotAllocationStrategy.java`** — the optional batch/lot extension.
- **`Main.java`** — a runnable demo exercising the full flow end-to-end.

A representative excerpt (the optimistic strategy's core retry loop — the single
most important method in this repository for the stated interview focus):

```java
private InventoryLevel mutate(InventoryKey key, Function<InventoryLevel, InventoryLevel> fn) {
    AtomicReference<InventoryLevel> row = rowFor(key);
    for (int attempt = 0; attempt < maxRetries; attempt++) {
        InventoryLevel current = row.get();
        InventoryLevel updated = fn.apply(current); // may throw business exception; propagate as-is
        if (row.compareAndSet(current, updated)) {
            return updated;
        }
        // CAS failed: another thread updated the row first. Retry with fresh data.
    }
    throw new ConcurrencyRetryExhaustedException(key, maxRetries);
}
```

Run it yourself: `java -cp <compiled-classes> lld.stockmanagement.Main` (see
`IMPLEMENTATION_GUIDE.md` for exact compile/run commands).

---

## 10. Data Structures and Algorithms

| Structure | Where used | Why | Alternative | Complexity |
|---|---|---|---|---|
| `ConcurrentHashMap<InventoryKey, ...>` | All three ledger implementations, both repositories, audit trail | Lock-striped, thread-safe map with O(1) average get/put and no global lock across unrelated keys — exactly the per-key isolation required (section 2, NFR). | `synchronized HashMap` | O(1) average get/put/computeIfAbsent, same as `HashMap`; `synchronized HashMap` would serialize *all* keys behind one lock, defeating the per-key isolation requirement. |
| `AtomicReference<InventoryLevel>` | `OptimisticVersionedInventoryLedger` | Lock-free CAS on an immutable object reference; the whole row updates atomically as one unit, including its version. | `volatile` field + manual synchronization | CAS retry loop is O(1) amortized under low/medium contention, degrading under pathological contention (bounded by `maxRetries`); volatile alone cannot provide read-modify-write atomicity. |
| `ReentrantLock` per row | `PessimisticLockInventoryLedger` | Explicit lock object scoped to exactly one key (lock striping), supports `tryLock`/timeouts if ever needed (not used here, but available without redesign). | `synchronized` block on a per-key monitor object | Functionally similar; `ReentrantLock` was chosen for its explicit, visible lock/unlock pairing in code (pedagogically clearer for an interview and the Javadoc) and room to add `tryLock` timeouts later. O(1) acquire/release under no contention; threads block (not spin) under contention. |
| `AtomicLong` with bit-packing | `AtomicCasInventoryLedger` | Packs two 32-bit counters into one CAS-able word so both fields update as one atomic unit. | Two independent `AtomicInteger`s | Two independent atomics cannot update both fields in one atomic step (see the class's Javadoc for the exact race this would introduce); the packed-long approach closes that window at the cost of a 32-bit range per field (acceptable for physical inventory) and bit-trick readability cost. O(1) per CAS attempt. |
| `CopyOnWriteArrayList` | `InMemoryAuditTrail` per-key history; listener sink lists | Read-mostly, append-occasionally access pattern (ops dashboards reading history far more often than movements are appended in bursts); readers never block, never see a torn list. | `Collections.synchronizedList(new ArrayList<>())` | Synchronized list still requires external synchronization for iteration (classic bug source); CoW avoids that entirely at the cost of O(n) copy per append — acceptable since audit append volume per key is not the hottest path (the ledger mutation is). |
| Immutable retry-loop pattern (functional `Function<InventoryLevel, InventoryLevel>`) | `OptimisticVersionedInventoryLedger.mutate` | Centralizes the CAS-retry boilerplate once instead of duplicating it across five methods. | Duplicate the loop in every method | DRY; the `mutate` helper is a pure function composition, easy to verify correct once and reuse. |
| Sorted list by expiry date (`Comparator.comparing(Lot::getExpiryDate)`) | `FefoLotAllocationStrategy.allocate` | FEFO requires soonest-expiring-first order. | Min-heap by expiry date | For the expected scale (a handful of lots per SKU/warehouse, not millions), a simple sort (`O(n log n)`) is simpler and clearer than maintaining a heap; a heap would only pay off if lots were allocated one-at-a-time continuously from a large, long-lived set, which is not this access pattern (lots are re-sorted fresh on each `allocate` call against the current, typically small, list). |

---

## 11. Concurrency and Thread Safety

This section is the heart of the problem. Every shared mutable state in this system,
and its concurrency story:

### Shared mutable state inventory
| State | Owner | Protection |
|---|---|---|
| Per-key `available`/`reserved`/`version` | `InventoryLedger` implementations | Strategy-specific — see below |
| SKU/warehouse catalog maps | `InMemorySkuRepository` / `InMemoryWarehouseRepository` | `ConcurrentHashMap` with `putIfAbsent` for register |
| Audit history lists | `InMemoryAuditTrail` | `ConcurrentHashMap<Key, CopyOnWriteArrayList>` |
| Listener registry | `InventoryService.listeners` | `CopyOnWriteArrayList` (listeners added at setup time, read on every mutation) |
| Reorder debounce flags | `ReorderAutomationListener.requestAlreadyRaised` | `ConcurrentHashMap` with `putIfAbsent`/`remove` |
| Lot quantities | `Lot.quantity` (mutable field) | `LotManager` serializes all access to a given key's lot list with `synchronized（lots)` — explicitly *not* part of the hot concurrent path (see `LotManager` Javadoc) |

### The core invariant and the race it must survive
The invariant is: **`available` must never go negative**, even when many threads
call `reserve` concurrently against the same `(sku, warehouse)` key, with the sum of
their requested quantities exceeding what's available. The classic naive bug:

```java
// WRONG — classic read-then-write race:
if (available >= quantity) {       // thread A and B both read available=10, quantity=6 each
    available -= quantity;         // both proceed: available becomes -2, both "succeed" -> oversold
}
```
Two threads can both pass the check before either writes, because "read" and "write"
are two separate, non-atomic steps. All three strategies below fix this by making
the check-and-mutate into one atomic unit, each via a different mechanism.

### Strategy 1 — Optimistic locking with a version column (`OptimisticVersionedInventoryLedger`)
Each row is an `AtomicReference<InventoryLevel>`. A mutation reads the current
immutable snapshot (including its `version`), computes a brand-new snapshot with
`version + 1`, and calls `compareAndSet(old, new)`. If another thread's mutation won
the race in between, the reference no longer equals `old`, the CAS fails, and the
loop retries against the now-current data.

- **Why correct:** `compareAndSet` is a single hardware CAS instruction — it is
  impossible for two threads to both succeed against the same `old` reference. A
  failed CAS makes zero changes (fully atomic all-or-nothing), so a thread can never
  observe or apply a partially-updated row.
- **Relational analogue:** `UPDATE inventory SET available = ?, version = version+1
  WHERE sku=? AND warehouse=? AND version=?` — a zero affected-row-count means retry.
- **Cost model:** cheapest under low/medium contention (no OS lock, no context
  switch, no blocking) — most attempts succeed on the first try. Degrades under very
  high contention on one hot key because failed attempts still burn CPU
  re-reading/re-validating before discovering they lost; `maxRetries` (default 1000)
  bounds this with `ConcurrencyRetryExhaustedException` instead of spinning forever.
- **Best for:** read-heavy or low-to-medium write contention per key — the common
  case for most SKUs.

### Strategy 2 — Pessimistic row locking (`PessimisticLockInventoryLedger`)
Each row owns a dedicated `ReentrantLock` (lock striping — one lock per key, not one
global lock). Every mutation acquires that lock before reading or writing the row's
plain `int`/`long` fields, and releases it in a `finally` block.

- **Why correct:** plain fields are safe here *without* being `volatile`/atomic
  because every access happens exclusively while holding the lock; `lock()`/
  `unlock()` establish the same happens-before edge a `synchronized` block would —
  whatever the lock holder wrote is guaranteed visible to the next thread acquiring
  the same lock (per the Java Memory Model's lock happens-before rule).
- **Relational analogue:** `SELECT ... FOR UPDATE` — acquire an exclusive row lock
  before even deciding if the operation is valid, so no other transaction can
  interleave.
- **Cost model:** never wastes work on doomed attempts (unlike optimistic) — a
  thread either waits or proceeds, it never computes a result that gets discarded.
  The cost: every operation serializes on the row lock even when no real conflict
  exists, and a slow/preempted lock holder stalls every other thread waiting on that
  same row (a risk CAS-based strategies don't have).
- **Best for:** very high contention on a small number of hot keys, where
  predictable serialization beats optimistic retry storms.

### Strategy 3 — Atomic compare-and-swap counters (`AtomicCasInventoryLedger`)
Both `available` and `reserved` are packed into one 64-bit `AtomicLong` (high 32
bits = available, low 32 bits = reserved), updated with one CAS per mutation.

- **Why correct, and why packing matters:** `reserve` must change both fields
  together (decrement one, increment the other) as one indivisible step. Two
  *independent* `AtomicInteger`s cannot do this atomically — a reader between the
  two separate CAS calls could observe `available` already decremented but
  `reserved` not yet incremented (units that exist in neither bucket momentarily).
  Packing both fields into one word and CASing the whole word closes that window:
  every observer sees the state fully-before or fully-after, never in between.
- **Relational analogue:** `UPDATE inventory SET available = available - ?, reserved
  = reserved + ? WHERE available >= ?` executed as one atomic statement.
- **Cost model:** cheapest per-operation of the three — no object allocation on the
  hot path (unlike optimistic, which allocates a new `InventoryLevel` per attempt),
  no lock/monitor (unlike pessimistic). Trade-off: reduced range (32 bits/field —
  ~2.1B units, ample for physical inventory) and reduced extensibility — adding a
  third atomically-coupled field (e.g. a version/fencing token) would not fit in 64
  bits and would force a redesign, whereas the optimistic strategy's immutable-
  object CAS trivially absorbs new fields by construction.
- **Best for:** the single hottest keys in the system (e.g. a flash-sale SKU) where
  every nanosecond and every byte of per-op allocation matters.

### Concurrency-sensitive code (representative — see `src/` for the full, identical code)
```java
// AtomicCasInventoryLedger.reserve — see full file for complete method
while (true) {
    long current = row.get();
    int available = unpackAvailable(current);
    int reserved = unpackReserved(current);
    if (available < quantity) {
        throw new InsufficientStockException(key, quantity, available);
    }
    long updated = pack(available - quantity, reserved + quantity);
    if (row.compareAndSet(current, updated)) {
        return toLevel(updated, 0L);
    }
    // CAS lost the race; loop re-reads the now-current packed value and re-validates.
}
```

### Lock granularity and deadlock analysis
- **Granularity:** per-`InventoryKey` (per SKU+warehouse pair), never global. This
  is a deliberate choice validated by the NFRs in section 2 ("contention on one SKU
  must never slow down operations on another").
- **Deadlock freedom:** every strategy acquires at most **one** lock/CAS-target per
  operation (one row). There is no scenario in this design where a single operation
  needs to hold two locks simultaneously (e.g. no "reserve from warehouse A and
  warehouse B atomically" operation exists), so lock-ordering deadlocks are
  structurally impossible, not just avoided by convention. If a future requirement
  needed cross-warehouse atomic transfer, that would require a documented lock-
  ordering discipline (e.g. always lock the lexicographically-smaller key first) —
  noted here precisely because it is *not* currently needed, not implemented
  speculatively.
- **Visibility:** optimistic and CAS strategies get visibility for free from
  `AtomicReference`/`AtomicLong`'s CAS semantics (which are volatile reads/writes
  under the hood); pessimistic strategy gets it from lock happens-before. No strategy
  relies on plain-field visibility outside its guard.
- **Concurrent collections:** `ConcurrentHashMap` is used everywhere a map is shared
  across threads; never a plain `HashMap` behind ad-hoc synchronization.

### Why `InventoryService` itself needs no additional locking
`InventoryService` holds no mutable inventory state of its own — it only holds
immutable references to its collaborators (`skuRepository`, `ledger`, `auditTrail`)
and a `CopyOnWriteArrayList` of listeners. Every actual state mutation is delegated
to a collaborator that is independently thread-safe. This is a deliberate design
choice: pushing all contention-sensitive state behind the `InventoryLedger`
interface means the facade itself never needs synchronization, keeping its code
simple and impossible to get wrong with a missed lock.

### Proof: the concurrency tests
`tests/lld/stockmanagement/ledger/AbstractInventoryLedgerConcurrencyTest.java`
defines a shared scenario (seed 500 units, launch 50 threads × 40 reservation
attempts each = 2000 attempts against 500 units, using a `CountDownLatch` to start
all threads simultaneously) and asserts:
1. Exactly 500 reservations succeed — no more (no overselling), no fewer (no lost
   updates/spurious rejections).
2. The final `available` is exactly 0 and `reserved` is exactly 500.
3. `available` never goes negative.

This scenario runs against **all three strategies** via subclasses
(`OptimisticVersionedInventoryLedgerConcurrencyTest`,
`PessimisticLockInventoryLedgerConcurrencyTest`,
`AtomicCasInventoryLedgerConcurrencyTest`), plus a second scenario proving no lost
updates under concurrent `stockIn` increments. See section 15 and
`IMPLEMENTATION_GUIDE.md` for how to run them (all 28 tests pass, verified
repeatedly for flakiness — see this repo's final verification run).

---

## 12. Persistence and Database Design

**This implementation is deliberately in-memory.** Why a database is unnecessary
*for this exercise*:
- The problem is scoped to a single process, and the interview focus is
  concurrency-control algorithms, not persistence-layer design.
- `ConcurrentHashMap`-backed repositories and ledgers give the exact same external
  contract (`SkuRepository`, `WarehouseRepository`, `InventoryLedger`,
  `AuditTrail` interfaces) that a database-backed implementation would — swapping
  to real persistence means writing a new class behind each interface, not
  redesigning the domain.

**How this maps onto a real relational schema** (for the production-evolution
discussion, section 17):

```sql
CREATE TABLE sku (
    sku_code        VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    unit_of_measure VARCHAR(16) NOT NULL
);

CREATE TABLE warehouse (
    warehouse_code VARCHAR(64) PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    region         VARCHAR(64) NOT NULL
);

CREATE TABLE inventory_level (
    sku_code       VARCHAR(64) NOT NULL REFERENCES sku(sku_code),
    warehouse_code VARCHAR(64) NOT NULL REFERENCES warehouse(warehouse_code),
    available      INT NOT NULL CHECK (available >= 0),
    reserved       INT NOT NULL CHECK (reserved >= 0),
    version        BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (sku_code, warehouse_code)
);
-- Index: PK already covers the (sku_code, warehouse_code) lookup used by every ledger op.

CREATE TABLE stock_movement (
    movement_id          UUID PRIMARY KEY,
    sku_code             VARCHAR(64) NOT NULL,
    warehouse_code       VARCHAR(64) NOT NULL,
    movement_type        VARCHAR(32) NOT NULL,
    quantity             INT NOT NULL,
    resulting_available  INT NOT NULL,
    resulting_reserved   INT NOT NULL,
    reference_id         VARCHAR(128),
    occurred_at          TIMESTAMP NOT NULL,
    FOREIGN KEY (sku_code, warehouse_code) REFERENCES inventory_level(sku_code, warehouse_code)
);
CREATE INDEX idx_stock_movement_key_time ON stock_movement (sku_code, warehouse_code, occurred_at);
```

**Mapping each in-memory strategy to SQL:**
- **Optimistic** → `UPDATE inventory_level SET available=?, reserved=?,
  version=version+1 WHERE sku_code=? AND warehouse_code=? AND version=?`; check
  affected-row-count == 1, retry on 0.
- **Pessimistic** → `SELECT available, reserved FROM inventory_level WHERE
  sku_code=? AND warehouse_code=? FOR UPDATE`, validate, then `UPDATE ...` within the
  same transaction.
- **Atomic CAS** → `UPDATE inventory_level SET available = available - ?, reserved =
  reserved + ? WHERE sku_code=? AND warehouse_code=? AND available >= ?` as one
  statement; check affected-row-count == 1 to detect "insufficient stock" vs. a
  genuine failure.

**Transactions and isolation:** the pessimistic SQL mapping needs `REPEATABLE READ`
or stricter (to make the `FOR UPDATE` lock meaningful) — `READ COMMITTED` alone
would not prevent another transaction from reading stale data before the lock is
acquired, although it would still prevent the lost-update because `FOR UPDATE`
itself blocks. The optimistic and atomic-CAS SQL mappings work correctly even under
`READ COMMITTED` because their correctness comes from the `WHERE version=?` /
`WHERE available>=?` predicate at `UPDATE` time, not from transaction isolation
level.

---

## 13. Failure Scenarios

| Scenario | Handling |
|---|---|
| **Reserve more than available** | `InsufficientStockException` thrown before any state mutation (see invariant 4, section 5); caller surfaces "out of stock." |
| **Fulfill more than reserved** | Same exception type, thrown by `fulfillReservation` before any mutation — prevents a caller bug from silently corrupting `reserved` into an inconsistent state. |
| **Duplicate SKU registration (race between two registration calls)** | `putIfAbsent` ensures exactly one caller's registration wins atomically; the loser gets `DuplicateSkuException` deterministically, never a silently-overwritten catalog entry. |
| **Operation against an unregistered SKU/warehouse** | `SkuNotFoundException` / `WarehouseNotFoundException`, checked before touching the ledger — no partial state anywhere. |
| **Caller passes zero/negative quantity** | `InvalidQuantityException`, checked first in every ledger method before any read of the row — pure input validation, independent of concurrency. |
| **Pathological contention on one hot key (optimistic strategy)** | Bounded by `maxRetries` (default 1000); surfaces as `ConcurrencyRetryExhaustedException`, a distinct type from business failures so monitoring can page on it specifically as a system-health signal rather than a normal "out of stock" event. |
| **Caller crashes mid-operation (e.g. between `reserveForOrder` succeeding and the caller's own order record being saved)** | Out of this component's control by design — this is exactly why reservations exist as a distinct state from fulfillment: a crashed caller leaves stock `reserved`, not lost, and a reconciliation job (production extension, section 17) can release stale reservations past a TTL. This repo does not implement reservation TTLs/expiry (out of scope — no scheduler dependency), but the state model supports adding it without any ledger change: `releaseReservation` already exists for exactly this purpose. |
| **Duplicate order fulfillment request (retry from an upstream caller)** | Not idempotent as implemented — calling `fulfillOrder` twice for the same `orderId` will attempt to decrement `reserved` twice. This is a deliberate, documented scope cut (not every operation needs idempotency keys for an LLD exercise), explicitly called out as a production gap in section 17 ("idempotency keys on mutation calls, keyed by `referenceId`"). |
| **Resource exhaustion (unbounded audit trail growth)** | Acknowledged limitation of the in-memory design — see section 12/17 for the production evolution (retention policy, archival to cold storage). |
| **Lot allocation requests more than non-expired stock covers** | `FefoLotAllocationStrategy.allocate` throws `IllegalArgumentException` before mutating any lot's quantity (validated via a sum-of-available-lots check up front). |
| **Listener throws an exception during notification** | Propagates to the caller of the mutation method (not swallowed) — see `StockEventListener`'s Javadoc for why: a misbehaving listener is a bug that should be visible immediately in tests/development, not silently hidden. Production evolution (section 17) would wrap this in a try/catch-and-log per listener once listeners are no longer purely internal/trusted code. |

---

## 14. Performance and Scalability

**Bottlenecks and fixes, single-process:**
- **Bottleneck:** a single SKU receiving extremely high concurrent reservation
  traffic (flash sale). **Fix:** choose `AtomicCasInventoryLedger` for that
  key specifically — the `InventoryLedger` Strategy interface allows per-deployment
  (or even theoretically per-SKU, with a small router) selection without touching
  `InventoryService`.
- **Bottleneck:** audit trail growing unbounded in memory. **Fix (production):**
  periodic archival of old `StockMovement`s to cold storage (see section 17);
  *not* a concern for the interview-scope exercise itself.
- **Bottleneck:** `InventoryService.listeners` growing with many cross-cutting
  concerns, each adding synchronous latency to every mutation. **Fix (production):**
  move to an async event bus once there are more than a couple of lightweight
  listeners (section 17) — explicitly not done here because it's unnecessary
  complexity for 1-2 listeners (YAGNI).

**Multi-threaded (within one process) optimizations already applied:**
- Per-key lock/CAS granularity (not global) across all three ledger strategies —
  this is the single biggest scalability decision in the design.
- `ConcurrentHashMap.computeIfAbsent`/`putIfAbsent` everywhere a key might be
  created concurrently with first use, avoiding a coarser "check-then-create" race
  that a plain `HashMap` + external lock would need.
- `CopyOnWriteArrayList` for read-heavy audit/listener lists, avoiding reader
  blocking entirely.

**Distributed extension (explicitly *not* built here, and why that's correct for
an LLD exercise — "do not over-distribute an LLD problem"):**
- If inventory needed to be shared across multiple service instances/nodes (e.g.
  horizontally scaled order-processing fleet), the per-process
  `AtomicReference`/`ReentrantLock`/`AtomicLong` strategies stop being sufficient —
  they only coordinate threads within one JVM. The natural evolution:
  - Move the ledger to a real database and use the SQL mappings in section 12
    (optimistic version column or `SELECT ... FOR UPDATE`) — this is usually the
    right first step since it needs no new infrastructure beyond the DB already
    needed for durability.
  - For extreme throughput on a handful of hot SKUs, a distributed atomic counter
    (e.g. Redis `DECRBY` with a Lua script enforcing the floor check, or a
    dedicated counter service) mirrors the `AtomicCasInventoryLedger` strategy at
    the distributed-systems layer.
  - Distributed locks (Zookeeper/etcd/Redis Redlock) mirror the pessimistic
    strategy but are **not** the first choice here — they add an availability
    dependency (lock service down = can't sell anything) for a problem the DB
    transaction already solves more simply.
  - This is deliberately *not* implemented in this repo: introducing distributed
    coordination for a single-process LLD exercise would be over-engineering
    exactly as the instructions warn against.

---

## 15. Testing Strategy

**Unit tests** (`tests/lld/stockmanagement/SkuTest.java`) — value-object validation
and the `equals`/`hashCode` contract (identity by business key, not by reference or
by other fields).

**Behavioral/integration tests** (`tests/lld/stockmanagement/InventoryServiceTest.java`)
— the facade's full orchestration: stock-in, reserve, release, fulfill, adjust, each
asserting both the resulting `InventoryLevel` and the audit trail; boundary cases
(reserve exactly all remaining stock, adjust below zero); negative cases (unknown
SKU, insufficient stock produces no audit entry); Observer behavior (low-stock alert
fires/doesn't fire at the threshold boundary; reorder automation raises exactly one
request and debounces correctly across a restock cycle).

**Concurrency / failure-injection tests**
(`tests/lld/stockmanagement/ledger/AbstractInventoryLedgerConcurrencyTest.java` and
its three strategy-specific subclasses) — the mandated proof that concurrent
decrement of the same SKU across 50 threads never oversells, with a second scenario
proving no lost updates under concurrent increments. Plus a strategy-specific test
on the optimistic ledger asserting the version column advances exactly once per
successful write under contention, and a distinct-exception-type test showing
`ConcurrencyRetryExhaustedException` is never conflated with `InsufficientStockException`.

**Optional-extension tests** (`tests/lld/stockmanagement/lot/FefoLotAllocationStrategyTest.java`)
— FEFO ordering, spill-over across multiple lots, expired-lot exclusion, and the
insufficient-non-expired-stock negative case.

**How to run:**
```bash
# Compile (main + tests) against the JUnit 5 console-standalone jar:
javac -d out -cp junit-platform-console-standalone.jar $(find src tests -name '*.java')

# Run the full suite:
java -jar junit-platform-console-standalone.jar --class-path out --scan-class-path
```
All 28 tests pass; see this repo's build verification notes in
`IMPLEMENTATION_GUIDE.md` for the exact command and result used to validate this
submission, including repeated runs confirming the concurrency tests are not flaky.

---

## 16. Trade-offs and Alternatives

| Decision | Chosen approach | Alternative | Pros/cons | When the alternative wins |
|---|---|---|---|---|
| Concurrency control | Three strategies behind one interface, caller picks | One single strategy | Chosen approach directly serves the interview's explicit comparison requirement and lets the codebase demonstrate all three correctly; cost is more code to maintain for an exercise. | In a real single production system, you'd typically pick **one** (usually the DB-transaction / optimistic-version-column approach, since it needs no extra infrastructure) rather than carry three implementations. |
| Reservation model | Two counters (`available`, `reserved`) | One counter, orders decrement directly | Two counters correctly model "promised but not yet shipped," supporting cancellation without re-deriving history; cost is a slightly larger state machine. | A single counter wins if backorders aren't a concern and orders are fulfilled atomically at purchase time with no cancellation window (e.g. a vending machine). |
| Audit trail storage | In-memory, append-only list per key | Durable event log (Kafka) | In-memory is sufficient and simplest for the stated scope; a durable log is necessary once the audit trail must survive process restarts or feed analytics. | Production systems need the durable log — see section 17. |
| Observer notification | Synchronous, in-process | Asynchronous event bus | Synchronous keeps the design simple and means a mutation and its side effects are observably atomic from the caller's perspective (no eventual-consistency window to reason about in tests); cost is listener latency directly impacting mutation latency. | Async wins once there are many listeners, or any listener does non-trivial I/O (e.g. sending an email) that shouldn't block the hot path. |
| Reorder debounce mechanism | Explicit state flag (`requestAlreadyRaised`), cleared on restock | Time-based debounce window | State-based is deterministic and testable without needing to mock time; a time window risks either suppressing a genuinely new problem or needing a scheduler dependency. | A time-based window could be preferable if restocking isn't reliably observable as a discrete event (not the case here — `stockIn` is always explicit). |
| Lot allocation policy | FEFO (First-Expired-First-Out) | FIFO (First-In-First-Out) | FEFO minimizes expiry write-offs for perishable/date-sensitive goods, matching the "expiry" requirement explicitly called out in scope. | FIFO wins for non-perishable goods where receipt order matters more than expiry (e.g. warranty/serial tracking), or where expiry dates don't exist at all. |
| Cross-field atomicity in the CAS strategy | Pack both counters into one `AtomicLong` | Two independent `AtomicInteger`s | Packing closes the "neither bucket" visibility window (see section 11); cost is bit-trick complexity and a 32-bit-per-field range ceiling. | Two independent atomics would be fine if the two fields were never required to be consistent with each other at every instant — not true here, since `onHand` correctness matters. |

---

## 17. Production Evolution

Kept explicitly separate from the core interview solution above — these are the
concerns a staff engineer would raise when asked "how would this actually ship and
run," not requirements to build for this exercise:

- **Durable persistence.** Replace in-memory repositories/ledger/audit-trail with
  database-backed implementations behind the same interfaces (section 12 gives the
  exact schema and SQL mapping per strategy) — no change needed above those
  interfaces.
- **Idempotency.** Add idempotency keys (keyed by `referenceId`/`orderId`) to
  `reserveForOrder`/`fulfillOrder`/`releaseReservation` so retried upstream calls
  (e.g. from an at-least-once message queue) don't double-apply.
- **Reservation TTL/expiry.** A background reconciliation job that calls
  `releaseReservation` for reservations older than a configurable TTL with no
  corresponding fulfillment — handles the "caller crashed mid-checkout" failure
  scenario (section 13) without any ledger redesign.
- **Asynchronous observer notification.** Once listeners do non-trivial I/O (e.g.
  paging, emailing purchasing), move `StockEventListener` notification onto an async
  event bus (Kafka/SQS) with retry and dead-letter handling, decoupling listener
  latency/failures from mutation latency.
- **Distributed concurrency control.** See section 14's distributed-extension
  discussion — DB transactions first, distributed atomic counters for extreme hot
  keys, distributed locks only if genuinely necessary.
- **Demand-forecast-driven reorder points.** Replace the static `ReorderPolicy`
  min/max with a model computing reorder point from historical demand velocity and
  supplier lead time — a separate forecasting system feeding `ReorderPolicy` values,
  not a change to `ReorderAutomationListener`'s mechanism.
- **Observability.** Metrics (reservation success/failure rate per SKU, CAS retry
  counts/latency histograms per strategy, audit trail growth rate), structured
  logging on every `ConcurrencyRetryExhaustedException` (a system-health signal —
  see section 8's exception table), and distributed tracing across the
  reserve→fulfill lifecycle of an order.
- **Operational concerns.** Audit trail retention/archival policy; catalog caching
  (SKU/warehouse lookups are read-heavy and rarely change — a cache in front of the
  repository, invalidated on register, is a natural addition); alerting routing
  (`LowStockAlertListener`'s sink is currently a simple `Consumer<String>` — in
  production this becomes a real paging/Slack integration, still behind the same
  interface).
- **Fault tolerance.** Circuit breakers/backpressure if the ledger ever moves behind
  a network call (distributed extension); currently moot for the in-process design,
  but the `InventoryLedger` interface boundary is exactly where such resilience
  wrapping (e.g. a Decorator — see section 7's "patterns not used" note) would be
  added without touching `InventoryService`.
