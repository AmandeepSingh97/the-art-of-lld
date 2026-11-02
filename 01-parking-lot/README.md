# Parking Lot — Low-Level Design (Staff Engineer Edition)

Java 17+ · Package `lld.parkinglot` · Source in `src/`, tests in `tests/`, diagrams in `diagrams/`

---

## 1. Problem Understanding

**Plain explanation.** Design a system that manages a multi-floor parking
garage: vehicles of different sizes arrive at one of several entry gates,
are assigned a spot, and receive a ticket; later they leave through one of
several exit gates, pay a fee computed from how long they stayed (or a flat
fee), and the spot becomes available again. Garage staff can also take
individual spots out of service (maintenance, damage) and bring them back.

**Real-world use case.** This is the system behind any commercial garage,
airport parking structure, or mall parking deck — anywhere physical spots
are a scarce, shared resource allocated to distinct vehicle classes
(motorcycle/car/bus) with time-based or flat billing.

**What the interviewer is evaluating.**
- Can you model a physical, stateful resource (a spot) correctly and defend
  its lifecycle?
- Can you design clean seams (interfaces) so allocation policy, pricing
  policy, and payment method are each swappable without rewriting the core?
- Do you *actually* reason about concurrency, or just say "we'd use a lock"
  without naming what the lock protects and why?
- Do you keep the design proportioned to a 45-60 minute interview — no
  microservices, no Kafka, no premature distribution?

**In scope.**
- Multiple floors, multiple vehicle types (motorcycle/car/bus-or-truck),
  multiple spot sizes (small/medium/large) with a compatibility rule.
- Multiple entry and exit gates operating concurrently.
- Nearest-available-spot allocation.
- Ticket issuance and closure.
- Hourly and flat-rate pricing strategies.
- Multiple payment methods (cash/card/wallet).
- Admin operation: mark a spot out of service / restore it.
- Thread-safety for all of the above under concurrent gate usage.

**Out of scope (see Section 17 for how each would be added).**
- Reservations/pre-booking a spot before arrival.
- Dynamic/surge pricing, discounts, subscriptions, validation vouchers.
- Multi-garage, multi-tenant, or distributed (multi-process) deployment.
- Physical hardware integration (license-plate OCR, boom barriers, sensors).
- A persistent database (see Section 12 for why in-memory is the right
  choice for *this* exercise, and what changes if it weren't).
- AuthN/AuthZ for the admin operations.

---

## 2. Requirement Gathering

### Functional — essential (interview-necessary)
1. Entry gate accepts a vehicle and, if a compatible spot exists, issues a ticket.
2. The lot supports 3 vehicle types and 3 spot sizes with a fit rule: a spot
   can host any vehicle type whose footprint is ≤ its size (motorcycle fits
   anywhere; car fits medium/large; bus/truck fits large only).
3. Allocation picks the *nearest* available compatible spot (floor closest
   to the entry gate used, then smallest sufficient size, then lowest spot
   number).
4. Exit gate accepts a ticket id, computes the fee via a pricing strategy,
   charges the driver via a chosen payment method, frees the spot, and
   closes the ticket.
5. A single license plate cannot hold two simultaneously active tickets.
6. Admin can mark a free spot out of service and later restore it; an
   occupied/reserved spot cannot be forced out of service.
7. The system reports current occupancy (counts per status) for dashboards.

### Functional — optional / explicitly deferred
- Reservations ahead of arrival.
- Monthly passes / subscriptions.
- Multiple simultaneous pricing tiers (weekday/weekend, time-of-day).
- Lost-ticket recovery flow with a penalty fee (modeled as a `LOST` ticket
  status in the enum for extensibility, but no recovery flow is implemented).

### Non-functional
| Concern | Requirement | How this design satisfies it |
|---|---|---|
| Performance | Allocation and spot-state transitions are O(log n) or better per op | `ConcurrentSkipListSet` index + single CAS (Section 10/11) |
| Scalability | Hundreds to low-thousands of spots, tens of concurrent gates | Lock-free per-spot CAS means no central bottleneck (Section 14) |
| Availability | No single global lock that can starve gates | No class-level lock anywhere in the hot path (Section 11) |
| Consistency | A spot is never handed to two vehicles; a plate never double-parks | Proven via CAS + atomic claim map + tests (Section 11, `tests/`) |
| Extensibility | New vehicle type, spot size, pricing, or payment method is a localized change | Strategy pattern for allocation/pricing/payment (Section 7) |
| Reliability | A failed payment must not silently free a spot nobody paid for | Payment happens *before* ticket close / spot free (Section 13) |
| Thread-safety | Must hold under concurrent, multi-gate load | Documented per-class in Section 11, verified by concurrency tests |

### Interview-necessary vs. production-extension split
Interview-necessary: everything in the "essential" functional list plus
basic concurrency correctness. Production-extension (Section 17):
persistence, distributed coordination, metrics/monitoring, hardware
integration, retries/idempotency keys for payment gateways, admin
authorization.

---

## 3. Clarifying Questions

These are the questions a staff candidate should actually ask in the first
5 minutes — not filler. Each includes why it matters and how the answer
would change the design. (The assumption actually used in this repo is
marked **[Assumed]**.)

1. **Can a smaller vehicle use a larger spot, and should it, given a choice?**
   Changes the allocation compatibility table and the "prefer smallest
   sufficient spot" tie-break. **[Assumed: yes it *can*, but the allocator
   prefers the smallest sufficient size to conserve large spots.]**

2. **Is pricing per-hour, per-fraction-of-hour, or flat regardless of duration?**
   Completely changes the `PricingStrategy` implementation and what
   `Ticket` needs to record. **[Assumed: both are required — hourly with a
   one-hour-minimum/round-up, and flat, selectable per deployment, hence the
   Strategy pattern rather than hard-coding one.]**

3. **Can the same license plate enter twice while already parked (e.g. data
   entry retry, two gates)?** If allowed, we'd need multiple concurrent
   tickets per plate; if not, we need an atomic "claim" mechanism.
   **[Assumed: not allowed — a plate has at most one active ticket at a
   time, enforced atomically (Section 5, invariant I-4).]**

4. **What happens if no spot is available?** Reject outright, queue the
   vehicle, or redirect to another floor/garage? Changes whether
   `parkVehicle` is synchronous-reject (simple) or needs a waiting-list/
   notification mechanism (materially more complex). **[Assumed:
   synchronous rejection via `NoAvailableSpotException` — queuing a
   physical vehicle isn't a software problem this lot can solve anyway;
   the car is still in the driveway.]**

5. **Can an admin take an *occupied* spot out of service (e.g. flagging
   it for maintenance the moment the car leaves)?** If "soft" out-of-service
   is needed, we'd need a `PENDING_OUT_OF_SERVICE` status. **[Assumed: no —
   only a currently-free spot can be marked out of service; staff must wait
   for the vehicle to leave. This is simpler and matches most real garages.]**

6. **Is "nearest" measured from the gate used, or always from a single
   fixed entrance?** Determines whether `Gate` needs a floor/location at
   all. **[Assumed: nearest to the specific gate used — each `Gate` records
   a `floorNumber`, and floors are ranked by distance from that gate, so a
   garage with gates on multiple levels behaves sensibly.]**

7. **Does payment happen at the exit gate (pay-on-exit) or at a separate
   pay station before walking back to the car (pay-and-display)?** Changes
   whether `Ticket` closure and payment are one atomic step or two.
   **[Assumed: pay-on-exit, single step — simpler and sufficient for an
   interview-scoped design; a pay-station variant is a Section 17 note.]**

8. **Do we need a database, or is in-memory acceptable for this exercise?**
   Determines whether `SpotRepository`/`TicketRepository` need
   transactions/isolation levels. **[Assumed: in-memory is acceptable and
   intentional for an LLD exercise — see Section 12 for the full
   justification and what would change with a real DB.]**

9. **Is a lost-ticket flow required?** If yes, a flat "lost ticket fee" and a
   way to still identify the vehicle (e.g. by plate) is needed.
   **[Assumed: out of scope, but `TicketStatus.LOST` exists in the enum so
   the extension point is visible and doesn't require a schema change.]**

10. **Should spot allocation be fair/FIFO across simultaneously-arriving
    gates, or is "whoever wins the CAS" acceptable?** Fairness would require
    a queue/ticketing mechanism per bucket, trading throughput for order.
    **[Assumed: no fairness guarantee is required — first successful CAS
    wins, consistent with how a real multi-lane garage behaves (whichever
    lane's barrier opens first gets the spot).]**

---

## 4. Domain Modelling

| Type | Name | Responsibility |
|---|---|---|
| Value object | `Vehicle` | Immutable (licensePlate, type); equality by plate |
| Value object | `Gate` | Immutable (id, name, floorNumber, ENTRY/EXIT kind) |
| Value object | `Money` | Immutable `BigDecimal` wrapper, avoids float rounding bugs |
| Entity | `ParkingSpot` | The one mutable, concurrency-critical unit; owns its own state machine (Section 6/11) |
| Entity | `Floor` | Structural grouping of spots; read-mostly |
| Aggregate root | `ParkingLot` | Static structure: name + ordered floors |
| Entity | `Ticket` | One stay's record: vehicle, spot, entry/exit time, fee, status |
| Entity | `Payment` | Append-only record of one charge attempt |
| Enum | `VehicleType`, `SpotSize`, `SpotStatus`, `TicketStatus`, `PaymentStatus`, `PaymentMethod` | Closed sets of domain states; `SpotSize.canFit(VehicleType)` centralizes the compatibility rule |
| Interface (Strategy) | `SpotAllocationStrategy` | *Which* spot a vehicle gets |
| Interface (Strategy) | `PricingStrategy` | *How much* a stay costs |
| Interface (Strategy) | `PaymentProcessor` | *How* a charge is settled |
| Interface (Repository) | `SpotRepository`, `TicketRepository`, `PaymentRepository` | *Where* state lives; seam for swapping in-memory ↔ persistent |
| Domain service | `ParkingLotService` | Orchestrates entry/exit; the only class that sequences repository + strategy calls |
| Domain service | `PaymentService` | Selects a `PaymentProcessor` by method and persists the result |
| Domain service | `AdminService` | Out-of-service/restore + occupancy reporting; kept separate from the core park/unpark flow (different authorization profile in production) |
| Facade (thin) | `EntryGateService`, `ExitGateService` | Binds one physical `Gate` to the shared `ParkingLotService` |
| Factory/Builder | `ParkingLotBuilder` | Assembles `ParkingLot`/`Floor`/`ParkingSpot` graph from a simple per-floor spec |

**Composition / association / inheritance:**
- `ParkingLot` *composes* `Floor` composes `ParkingSpot` — spots cannot
  outlive their floor (enforced by construction: spots are only ever
  created inside `ParkingLotBuilder.build()` and handed straight into a
  `Floor`'s constructor).
- `ParkingSpot` *aggregates* `Vehicle` — a vehicle exists independently;
  the spot merely references whichever vehicle currently occupies it.
- `Ticket` *associates* with one `Vehicle` and one `ParkingSpot` (not
  ownership — both outlive the ticket).
- No inheritance hierarchy is used anywhere in the domain model.
  `VehicleType`/`SpotSize` are enums with behavior (`canFit`), not a class
  hierarchy, because the compatibility rule is a closed, static table, not
  a set of types that each need independent polymorphic behavior — a
  `Car extends Vehicle` / `Motorcycle extends Vehicle` hierarchy was
  considered and rejected (Section 16) because it would require an
  `instanceof`-free way to express "a bus is-a vehicle but not is-a
  motorcycle", which an enum table already expresses more simply.

**Ownership/lifecycle:** `ParkingLot` is built once at startup and treated
as effectively immutable afterward (adding a floor at runtime is a
Section 17 production extension, not required here). `ParkingSpot` lives
for the lifetime of the garage and cycles through its state machine
indefinitely. `Ticket` and `Payment` are created per stay and never mutated
again after `Ticket.close()` — they become an immutable audit record.

---

## 5. Domain Invariants

| # | Invariant | Enforced where / how |
|---|---|---|
| I-1 | A spot is never assigned to more than one vehicle at a time | `ParkingSpot.tryReserve()` — single CAS `AVAILABLE→RESERVED` |
| I-2 | A spot can only be occupied after being reserved (no "occupy from thin air") | `ParkingSpot.occupy()` requires CAS `RESERVED→OCCUPIED`, throws otherwise |
| I-3 | A spot cannot be marked out of service while occupied or reserved | `ParkingSpot.tryMarkOutOfService()` — CAS only succeeds from `AVAILABLE` |
| I-4 | A license plate has at most one ACTIVE ticket at any time | `TicketRepository.tryClaimPlate()` — atomic `putIfAbsent` |
| I-5 | A ticket, once closed, is never re-opened or re-closed | `Ticket.close()` throws `IllegalStateException` unless current status is `ACTIVE` |
| I-6 | A vehicle is only charged for an exit that actually succeeds | `ParkingLotService.unparkVehicle` calls `PaymentService.pay` *before* `Ticket.close`/`spotRepository.freeSpot`/`releaseClaim` — a thrown `PaymentFailedException` leaves all three untouched |
| I-7 | A vehicle type can only occupy a spot size that legally fits it | `SpotSize.canFit(VehicleType)`, consulted by every allocation strategy before offering a spot |
| I-8 | Fees are never negative | `PricingStrategy` implementations validate `exitTime >= entryTime` and throw `IllegalArgumentException` otherwise |
| I-9 | Every reserved spot is eventually either occupied or released, never "stuck" reserved | `ParkingLotService.parkVehicle`'s catch block calls `releaseReservation` if `occupy()` throws |

---

## 6. Architecture and Class Diagram

See `diagrams/component-diagram.md` for the layered component view,
`diagrams/class-diagram.md` for the full class diagram (matches the actual
source 1:1), `diagrams/sequence-park-vehicle.md` and
`diagrams/sequence-unpark-vehicle.md` for the two key flows, and
`diagrams/state-diagram-spot.md` for the `ParkingSpot` state machine.

Architecture in one paragraph: gates (`EntryGateService`/`ExitGateService`)
are thin adapters that call one shared `ParkingLotService`, which
orchestrates three injected strategies (allocation, pricing, payment) and
two repositories (spots, tickets) without itself holding any lock — every
piece of actual shared mutable state (`ParkingSpot.status`, the plate-claim
map) is individually atomic, so composing them in `ParkingLotService`
requires no additional synchronization, only correct sequencing and
rollback-on-failure (Section 11 goes through this in full).

---

## 7. Design Patterns

| Pattern | Why needed | Where applied | Alternative considered | Why rejected |
|---|---|---|---|---|
| **Strategy** | Allocation policy, pricing, and payment method all vary independently and must be swappable without touching orchestration code | `SpotAllocationStrategy`, `PricingStrategy`, `PaymentProcessor` | A single `switch` on an enum inside `ParkingLotService` | Works for 2-3 cases but violates Open/Closed the moment a 3rd pricing model is added, and it forces unrelated concerns (allocation + pricing + payment) into one god-class method |
| **Repository** | Decouple "how state is found/mutated" from "what the business rule is" | `SpotRepository`, `TicketRepository`, `PaymentRepository` + in-memory impls | Let `ParkingLotService` hold `List<Floor>`/`Map` directly | Would hard-wire the service to one storage mechanism, make the CAS-vs-index concurrency design (Section 11) invisible/untestable in isolation, and block a future DB-backed implementation |
| **Builder** | Constructing a `ParkingLot` requires an unbounded, variable number of (floor, size, count) combinations | `ParkingLotBuilder` | A telescoping `ParkingLot` constructor, or a constructor taking `List<Floor>` built by hand at the call site | Telescoping constructors don't scale past 2-3 parameters; building floors by hand duplicates id-generation logic at every call site |
| **Facade** | Gates shouldn't need to know the internal sequencing of claim→allocate→occupy→ticket | `ParkingLotService` as the single entry point; `EntryGateService`/`ExitGateService` as even-thinner per-gate facades | Gates call `SpotRepository`/`TicketRepository` directly | Would leak the atomic claim/rollback sequencing (Section 11) into every gate, duplicating it and risking an inconsistent implementation at one gate but not another |
| **Immutable Value Object** | `Vehicle`, `Gate`, `Money` have no independent identity/lifecycle beyond their data | All three classes: final fields, no setters | Mutable POJOs with getters/setters | Mutable value objects shared across threads (e.g. a `Vehicle` referenced by both a `ParkingSpot` and a `Ticket`) would need their own synchronization for no benefit — immutability makes them trivially thread-safe |

**Patterns deliberately *not* used** (to avoid overengineering):
- **Singleton** for `ParkingLotService`/repositories — the caller (e.g. a
  DI container or `ParkingLotDemo.main`) owns exactly one instance per
  process already; a Singleton would only add global mutable state and
  make testing harder (tests in `tests/` construct fresh instances per test
  specifically to avoid shared state between tests).
- **Observer** for "notify when a spot frees up" — no requirement calls for
  push notifications; adding it now would be speculative generality.
- **Visitor** over vehicle types — the compatibility rule is a static table
  (`SpotSize.canFit`), not a set of behaviors that grows per vehicle type;
  a `Visitor` would add a class hierarchy and double-dispatch for no
  current benefit.
- **Command** for park/unpark operations — there's no requirement for
  undo/redo or operation queuing; wrapping each call in a `Command` object
  would be ceremony without payoff here.

---

## 8. API and Method Design

### `ParkingLotService` (the orchestration facade)
```java
Ticket parkVehicle(Vehicle vehicle, Gate entryGate)
    throws VehicleAlreadyParkedException, NoAvailableSpotException;

ExitReceipt unparkVehicle(String ticketId, Gate exitGate, PaymentMethod paymentMethod)
    throws InvalidTicketException, PaymentFailedException;
```
- **Validation responsibility:** null-checks on all parameters (fail fast,
  `NullPointerException` via `Objects.requireNonNull`); business-rule
  validation (duplicate plate, no spot, invalid ticket, failed payment) is
  reported via specific unchecked exceptions, all extending
  `ParkingLotException`, so callers can catch broadly or narrowly.
- **Request/response objects:** `Vehicle`/`Gate`/`PaymentMethod` are the
  request-side value objects; `Ticket` is returned from entry,
  `ExitReceipt` (ticket + payment) from exit — a dedicated response type
  rather than returning `Payment` alone, since callers typically need both.

### `EntryGateService` / `ExitGateService` (per-gate adapters)
```java
Ticket processEntry(Vehicle vehicle);
ExitReceipt processExit(String ticketId, PaymentMethod paymentMethod);
```
Thin pass-throughs; exist so gate-specific concerns (hardware driver,
per-gate metrics) have a home without bloating the facade (Section 4).

### `AdminService`
```java
void markSpotOutOfService(String spotId) throws InvalidSpotOperationException;
void bringSpotBackInService(String spotId) throws InvalidSpotOperationException;
Map<SpotStatus, Long> occupancySnapshot();
```

### `SpotAllocationStrategy` / `PricingStrategy` / `PaymentProcessor` (SPI for extension)
```java
Optional<ParkingSpot> reserveSpotFor(Vehicle vehicle, Gate entryGate);
Money calculateFee(Ticket ticket, Instant exitTime);
Payment charge(String ticketId, Money amount);
```
Each returns a value/`Optional` rather than throwing for the "normal
failure" case (`Optional.empty()` for "no spot"); exceptions are reserved
for genuinely exceptional conditions (unconfigured rate, infrastructure
failure).

### Exceptions (all unchecked, all extend `ParkingLotException`)
`NoAvailableSpotException`, `VehicleAlreadyParkedException`,
`InvalidTicketException`, `InvalidSpotOperationException`,
`PaymentFailedException`. Unchecked was chosen deliberately — see
Section 16 (checked vs. unchecked trade-off discussion).

---

## 9. Core Implementation

Full, compiling implementations live in `src/main/java/lld/parkinglot/`.
Key files, referenced rather than duplicated here to keep this README from
drifting out of sync with the actual code:

- **Domain model:** `model/ParkingSpot.java` (the concurrency-critical
  class — read its class javadoc first), `model/Ticket.java`,
  `model/Vehicle.java`, `model/Gate.java`, `model/Floor.java`,
  `model/ParkingLot.java`, `model/Payment.java`.
- **Enums:** `enums/VehicleType.java`, `enums/SpotSize.java` (the fit-rule
  table), `enums/SpotStatus.java`, `enums/TicketStatus.java`,
  `enums/PaymentStatus.java`, `enums/PaymentMethod.java`.
- **Strategies:** `strategy/NearestAvailableSpotAllocationStrategy.java`,
  `strategy/HourlyPricingStrategy.java`, `strategy/FlatRatePricingStrategy.java`,
  `strategy/CashPaymentProcessor.java`, `strategy/CardPaymentProcessor.java`,
  `strategy/WalletPaymentProcessor.java`.
- **Repositories:** `repository/InMemorySpotRepository.java` (the
  allocation-index design — read its class javadoc), `repository/InMemoryTicketRepository.java`,
  `repository/InMemoryPaymentRepository.java`.
- **Services:** `service/ParkingLotService.java` (read its class javadoc —
  it documents the full concurrency argument for `parkVehicle`),
  `service/PaymentService.java`, `service/AdminService.java`,
  `service/EntryGateService.java`, `service/ExitGateService.java`.
- **Factory:** `factory/ParkingLotBuilder.java`.
- **Runnable demo:** `app/ParkingLotDemo.java` — `main()` builds a 3-floor
  lot, runs the happy path for each vehicle type, demonstrates duplicate-entry
  rejection, an admin out-of-service/restore cycle, and a 20-thread
  concurrency stress test, printing results at each step.

To run it:
```bash
javac -d out $(find src -name '*.java')
java -cp out lld.parkinglot.app.ParkingLotDemo
```

---

## 10. Data Structures and Algorithms

| Structure | Used for | Why this one | Complexity | Alternative & trade-off |
|---|---|---|---|---|
| `AtomicReference<SpotStatus>` | Per-spot state | Lock-free CAS; no blocking, no deadlock possibility | O(1) per transition | `synchronized` method: correct but blocks threads even under *zero* contention; CAS degrades gracefully instead |
| `ConcurrentSkipListSet<ParkingSpot>` per (floor, size) | "Available spots, ordered by spot number" index | Sorted + concurrent + O(log n) `pollFirst`/`remove`, which is exactly "give me the nearest available spot" and "remove this specific spot from the pool" | O(log n) reserve/free/markOOS | A plain `TreeSet` + external lock: correct but serializes *all* buckets behind one lock even though different (floor,size) combinations never logically conflict; a `PriorityQueue`: not safely concurrent, and `remove(Object)` is O(n) |
| `ConcurrentHashMap<String, ParkingSpot>` | Spot lookup by id (for admin ops) | O(1) average lookup, safe concurrent reads/writes | O(1) | A `synchronized HashMap`: correctness-equivalent but coarser-grained locking for no benefit at this scale |
| `ConcurrentHashMap<String, Object>` used as a set | Active license-plate claims | `putIfAbsent` gives atomic test-and-set in one call, which is exactly invariant I-4 | O(1) | A `synchronized Set`: works, but `putIfAbsent` is a single already-atomic primitive — no need to hand-roll the check-then-act |
| `BigDecimal` via `Money` | Currency arithmetic | Exact decimal arithmetic; `double` accumulates binary rounding error that is unacceptable once real money is involved | O(1) per op (small, bounded precision) | `double`/`long` cents: `long` cents is viable and faster, rejected only because `BigDecimal` is more readable for an interview-scoped exercise; see Section 16 |
| `EnumMap<VehicleType, Money>` | Pricing/processor rate tables | Enum-keyed maps are array-backed internally — faster and more memory-compact than `HashMap` for a small closed key set | O(1), smaller constant than `HashMap` | `HashMap`: works identically, just slightly slower/heavier for no reason given the key set is a closed enum |

**Algorithm: nearest-spot allocation.** For a given vehicle and entry gate:
1. Rank floors by `|floor.number - gate.floorNumber|`, ties broken by
   ascending floor number — O(F log F) for F floors (trivially small, F is
   a handful in any real garage).
2. For each floor in that order, try each compatible `SpotSize` in
   ascending size order (smallest-sufficient-first) — at most 3 sizes.
3. For the first (floor, size) pair with a non-empty bucket,
   `pollFirst()`+CAS reserve it — O(log n) where n is spots in that bucket.

Overall: O(F log F + F·S·log n) per allocation, which for realistic garage
sizes (F ≤ 20, S ≤ 3, n ≤ a few hundred per bucket) is effectively O(1) in
practice.

---

## 11. Concurrency and Thread Safety

This section is mandatory even though nothing here uses a
distributed/multi-process design — correctness under concurrent, in-process
multi-threaded access (multiple entry/exit gate threads) is a hard
functional requirement, not an afterthought.

### 11.1 What is actually shared mutable state?
1. `ParkingSpot.status` (an `AtomicReference<SpotStatus>`) and
   `ParkingSpot.currentVehicle` — read/written by any gate thread touching
   that spot.
2. The per-(floor,size) `ConcurrentSkipListSet<ParkingSpot>` "available
   index" inside `InMemorySpotRepository` — written by every allocation,
   free, mark-out-of-service, and restore operation.
3. The `activePlates` map inside `InMemoryTicketRepository` — written by
   every park/unpark operation.
4. `Ticket.status`/`exitTime`/`feeCharged` — written exactly once, by the
   thread that successfully calls `unparkVehicle` for that ticket.

Nothing else is mutable shared state: `Vehicle`, `Gate`, `Money`, `Payment`,
`ParkingLot`/`Floor` (structure, not spot state) are all immutable after
construction.

### 11.2 Why there are no explicit locks anywhere in the hot path
Every one of the four shared structures above exposes only operations that
are **individually atomic** at the data-structure level:
- `AtomicReference.compareAndSet` for spot state (Section 6 state diagram —
  every arrow is one CAS).
- `ConcurrentSkipListSet.pollFirst()` / `.remove(Object)` are themselves
  atomic, linearizable operations.
- `ConcurrentHashMap.putIfAbsent` is atomic test-and-set.

`InMemorySpotRepository`'s central invariant (stated in its class javadoc)
is: **a spot's availability is represented in exactly one place at a time —
either it sits in the available index, or it does not — and whoever
atomically removes it from the index is the sole owner of the next state
transition.** This means composing "find a spot" + "claim it" never needs a
lock spanning both steps, because removal from the index *is* the claim.

This is a conscious **lock-free over lock-based** design choice: the
alternative (one `synchronized` block per floor, or one big lock over the
whole repository) would be simpler to write but would serialize every gate
in the garage behind a single mutex — exactly the kind of contention a
multi-gate garage is built to avoid. See Section 16 for the explicit
trade-off writeup.

### 11.3 Race conditions considered and how each is closed
| Race | Scenario | How it's prevented |
|---|---|---|
| Double allocation | Two gates both try to grab the same nearest spot | `pollFirst()` on the shared `ConcurrentSkipListSet` atomically hands the element to exactly one caller; the loser simply polls the next element instead |
| Allocate vs. admin out-of-service | Gate reserves spot X at the same instant staff tries to disable it | Both paths go through the *same* index-removal gate (`bucket.remove(spot)` / `pollFirst()`); whichever succeeds in removing it from the `ConcurrentSkipListSet` wins, the other sees `false`/empty and reports the natural outcome (`NoAvailableSpotException` or "could not disable, already taken") |
| Double parking the same plate | Same license plate submitted at two gates simultaneously | `TicketRepository.tryClaimPlate` — atomic `putIfAbsent`; exactly one wins, the other gets `VehicleAlreadyParkedException` |
| Reservation leaked on partial failure | `occupy()` throws after a spot was reserved but before a ticket is created | `ParkingLotService.parkVehicle`'s `catch` block explicitly calls `spotRepository.releaseReservation(spot)` *and* `ticketRepository.releaseClaim(plate)` — no spot or plate is left "stuck" |
| Charging twice / closing twice | Same ticket id passed to `unparkVehicle` concurrently or retried | `Ticket.close()` itself checks `status == ACTIVE` and throws `IllegalStateException` otherwise, and `ParkingLotService` checks `ticket.getStatus() != ACTIVE` up front and throws `InvalidTicketException` — the second caller never reaches the payment step |
| Visibility of `currentVehicle` | Thread A sets it while occupying, Thread B (a later exit) reads it | `currentVehicle` is `volatile`; more fundamentally, the only way Thread B can ever obtain a reference to that `ParkingSpot` in the first place is through a `Ticket` that was `save()`d *after* `occupy()` completed on the owning thread, which establishes happens-before through the `ConcurrentHashMap` the ticket is stored in |

### 11.4 Deadlock analysis
There are **zero** `synchronized` blocks and **zero** explicit `Lock`
acquisitions anywhere in `src/main/java/lld/parkinglot`. A design with no
lock acquisition cannot deadlock by definition — this isn't an incidental
property, it's the reason the lock-free design was chosen for the hot path
(Section 16 discusses what *would* need a lock if requirements grew, e.g.
multi-spot atomic reservation for a bus+trailer).

### 11.5 Optimistic vs. pessimistic locking
Every state transition in this system is **optimistic**: callers attempt a
CAS/atomic op and handle "it didn't work" by trying the next candidate or
reporting a domain-level failure — there is never a "wait until the
resource is free" (pessimistic) code path. This fits the access pattern: a
spot or plate conflict is a rare event relative to total throughput (most
allocation attempts succeed on the first try), so optimistic concurrency
avoids paying any blocking/locking cost in the common case.

### 11.6 Concurrency-sensitive code, annotated
```java
// ParkingSpot.java — the single atomic unit the whole system is built on
public boolean tryReserve() {
    return status.compareAndSet(SpotStatus.AVAILABLE, SpotStatus.RESERVED);
}
```
```java
// InMemorySpotRepository.java — index removal is the sole gate on state transitions
public Optional<ParkingSpot> reserveSpot(int floorNumber, SpotSize size) {
    NavigableSet<ParkingSpot> candidates = bucket(floorNumber, size);
    ParkingSpot candidate;
    while ((candidate = candidates.pollFirst()) != null) {
        if (candidate.tryReserve()) {
            return Optional.of(candidate);
        }
    }
    return Optional.empty();
}
```
```java
// InMemoryTicketRepository.java — atomic test-and-set closes the duplicate-plate race
public boolean tryClaimPlate(String licensePlate) {
    return activePlates.putIfAbsent(licensePlate, PRESENT) == null;
}
```
These three snippets, composed by `ParkingLotService.parkVehicle` with
explicit rollback on failure, are the entire concurrency story of this
system — see that class's javadoc for the end-to-end walkthrough.

### 11.7 Verifying the claims
`tests/.../model/ParkingSpotTest.java#exactlyOneThreadWinsConcurrentReservation`,
`tests/.../repository/InMemorySpotRepositoryTest.java#concurrentReservationsAcrossManyThreadsNeverDoubleAllocateASpot`,
and `tests/.../service/ParkingLotServiceTest.java#manyEntryGatesCannotOverbookOrDoubleAllocateSpotsConcurrently`
each spin up 40-100 threads racing on a shared, latch-synchronized start
and assert uniqueness/exact-count outcomes — these are not just "it didn't
throw" tests; a naive check-then-act implementation would fail their
uniqueness assertions under real contention.

---

## 12. Persistence and Database Design

**This design is intentionally in-memory**, via the `SpotRepository`/
`TicketRepository`/`PaymentRepository` interfaces backed by
`InMemory*Repository` implementations. This is not a shortcut — it's the
correct scope for an LLD interview exercise, for three reasons:
1. The interesting problem here is *correct concurrent in-process state
   management* (Section 11), which a database would largely delegate to
   row-level locking/transactions — hiding exactly the reasoning the
   interview is meant to probe.
2. A single garage's state (hundreds to low-thousands of spots, at most a
   few thousand open tickets at once) comfortably fits in memory with no
   durability requirement stronger than "survive this process's lifetime."
3. Introducing a database (schema, migrations, an ORM or JDBC layer) adds
   substantial surface area with zero bearing on the actual design
   questions (allocation policy, pricing, concurrency) this exercise is
   scoped to answer.

**If persistence were required** (e.g. the garage's state must survive a
process restart, or multiple processes must share one garage — see
Section 14 for why that's a materially different, distributed problem),
here is the schema this design maps onto directly:

```sql
CREATE TABLE floor (
    floor_number INT PRIMARY KEY
);

CREATE TABLE parking_spot (
    id            VARCHAR(32) PRIMARY KEY,
    floor_number  INT NOT NULL REFERENCES floor(floor_number),
    spot_number   INT NOT NULL,
    size          VARCHAR(16) NOT NULL,      -- SMALL/MEDIUM/LARGE
    status        VARCHAR(16) NOT NULL,      -- AVAILABLE/RESERVED/OCCUPIED/OUT_OF_SERVICE
    version       BIGINT NOT NULL DEFAULT 0, -- optimistic-lock column, see below
    UNIQUE (floor_number, spot_number)
);
CREATE INDEX idx_spot_allocation ON parking_spot (floor_number, size, status, spot_number);

CREATE TABLE ticket (
    id              VARCHAR(32) PRIMARY KEY,
    license_plate   VARCHAR(16) NOT NULL,
    vehicle_type    VARCHAR(16) NOT NULL,
    spot_id         VARCHAR(32) NOT NULL REFERENCES parking_spot(id),
    entry_gate_id   VARCHAR(32) NOT NULL,
    entry_time      TIMESTAMP NOT NULL,
    exit_gate_id    VARCHAR(32),
    exit_time       TIMESTAMP,
    fee_charged     DECIMAL(10,2),
    status          VARCHAR(16) NOT NULL      -- ACTIVE/PAID_AND_CLOSED/LOST
);
-- Partial unique index: at most one ACTIVE ticket per plate (Postgres syntax)
CREATE UNIQUE INDEX uq_active_ticket_per_plate
    ON ticket (license_plate) WHERE status = 'ACTIVE';

CREATE TABLE payment (
    id          VARCHAR(32) PRIMARY KEY,
    ticket_id   VARCHAR(32) NOT NULL REFERENCES ticket(id),
    amount      DECIMAL(10,2) NOT NULL,
    method      VARCHAR(16) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    created_at  TIMESTAMP NOT NULL
);
CREATE INDEX idx_payment_ticket ON payment (ticket_id);
```

**Transactions/isolation mapping from the in-memory design:**
- The `uq_active_ticket_per_plate` partial unique index *is* the database
  equivalent of `tryClaimPlate`'s `putIfAbsent` — an `INSERT` that violates
  it fails atomically, no application-level lock needed.
- `ParkingSpot`'s CAS becomes an optimistic-lock `UPDATE ... WHERE id = ?
  AND status = 'AVAILABLE' AND version = ?` (the `version` column); zero
  rows updated means "lost the race", exactly mirroring `compareAndSet`
  returning `false`.
- `READ COMMITTED` isolation is sufficient for spot allocation given the
  above — the correctness is in the `WHERE status = 'AVAILABLE'` predicate
  plus optimistic version check, not in `SERIALIZABLE` isolation.
- The exit flow (payment → close ticket → free spot) would be one DB
  transaction, so a crash mid-exit rolls back cleanly to "still ACTIVE,
  still OCCUPIED" — the same safety the in-memory ordering gives today
  (Section 13).

---

## 13. Failure Scenarios

| Scenario | Behavior | Why this is safe |
|---|---|---|
| No compatible spot anywhere | `parkVehicle` throws `NoAvailableSpotException`; the plate claim taken moments earlier is rolled back via `releaseClaim` | The vehicle can be retried (e.g. redirected to another garage) without being permanently blocked from ever parking again |
| Spot reserved but `occupy()` throws (e.g. a bug, or a future validation) | `catch` block releases both the spot reservation and the plate claim | No spot or plate is ever left "stuck" in a reserved-but-unoccupied, unreclaimed state |
| Payment declined/fails | `PaymentFailedException` propagates; ticket remains `ACTIVE`, spot remains `OCCUPIED`, plate remains claimed | The driver can retry with a different payment method; no spot is ever given away for free (invariant I-6) |
| Duplicate/retried exit request for the same ticket | Second call sees `status != ACTIVE` and throws `InvalidTicketException` before touching payment | No double-charge, no double-free of the spot |
| Admin tries to disable a spot that just got occupied | `markOutOfService` returns `false` (its `bucket.remove` fails because the spot already left the index) | `AdminService` surfaces this as `InvalidSpotOperationException` with the spot's actual current status — no corruption, no forced eviction of a parked vehicle |
| Unknown ticket id at exit | `InvalidTicketException` | Fails fast with a clear message rather than a `NullPointerException` deep in the call stack |
| Process crash mid-exit (payment succeeded, but before `Ticket.close()`/`freeSpot()` run) | **Known gap in the in-memory design** — see below | Addressed via idempotency key + outbox pattern in a persistent deployment (Section 17) |
| Resource exhaustion: garage legitimately full | Allocation naturally returns empty once every bucket is drained; no exception, no crash, no memory growth — rejections are O(1) | The whole point of `NoAvailableSpotException` as a *normal*, expected outcome rather than a system failure |
| Two threads both discover `markOutOfService` fails on the same spot, immediately after it was freed by yet another thread | Resolved by the same index-ownership rule (Section 11.2) — whichever thread's `remove` call wins proceeds, the other sees `false` | No two operators ever believe they both successfully disabled the same spot |

**Explicit limitation — payment/commit gap:** if the process crashed
between `paymentService.pay(...)` succeeding and `ticket.close(...)` /
`spotRepository.freeSpot(...)` running, the in-memory design as built today
would lose the in-flight state entirely (everything is in-memory). In a
persistent deployment this becomes: wrap the three steps in one DB
transaction (Section 12) so a crash rolls back to "payment never
happened" from the database's point of view, *and* make the
`PaymentProcessor.charge` call idempotent via an idempotency key derived
from `ticketId` (so a retried charge against a real payment gateway after a
crash doesn't double-charge the driver even though our own transaction
rolled back). This is flagged here explicitly rather than glossed over,
and is revisited in `STAFF_LEVEL_DEEP_DIVE.md`.

---

## 14. Performance and Scalability

**Where the bottleneck would actually be, if any:** with the lock-free
per-(floor,size) bucket design, throughput scales with the number of
distinct buckets a garage has, not with the number of gates — two gates
allocating `CAR`-sized spots on different floors never contend at all; two
gates allocating the *same* (floor,size) bucket only contend at the single
`pollFirst()` CAS, which is a few nanoseconds, not a lock hold.

**Single-process, multi-threaded optimizations already applied:**
- Per-bucket indices instead of one global structure (Section 10) — avoids
  false contention between unrelated floors/sizes.
- `EnumMap` for rate tables — O(1) with minimal overhead for small closed
  key sets.
- `CopyOnWriteArrayList` for per-ticket payment history — optimizes for the
  actual read/write ratio (many reads of a receipt, at most 1-2 writes per
  ticket).

**What would *not* scale, and the fix, without over-engineering it:**
- A single JVM process is, by construction, a single point of failure and
  a ceiling on throughput. The fix for "one garage, too much load for one
  process" is **not** sharding this design across machines (a parking
  garage has a physical count of spots in the hundreds — there is no
  realistic load that saturates one modern JVM's CAS throughput for that).
  The fix, if it were ever needed, is a single-writer process per garage
  behind a request queue, which keeps the entire concurrency model in this
  document unchanged — see Section 17 for how multi-garage, not
  multi-process-per-garage, is the dimension that actually needs
  distribution.
- `ConcurrentSkipListSet` operations are O(log n); even a pathologically
  large single floor (tens of thousands of spots, unrealistic for a
  physical garage) stays well within microsecond latency.

**Explicitly not distributing this:** a parking garage's spot count is
bounded by physical reality (floors × spots-per-floor), never by request
volume in the way a web-scale service is. Introducing Kafka, sharding, or a
distributed lock manager here would be solving a problem this domain does
not have — see `STAFF_LEVEL_DEEP_DIVE.md`'s "overengineering" question for
the fuller argument, and Section 17 for what *does* get distributed
(multiple garages, a cross-garage occupancy dashboard) versus what
deliberately stays single-process (one garage's own spot allocation).

---

## 15. Testing Strategy

Tests live in `tests/java/lld/parkinglot/`, mirroring the `src` package
structure, using JUnit 5.

| Category | Representative test(s) |
|---|---|
| Unit — state machine | `model/ParkingSpotTest` — every legal transition, every illegal transition correctly throws |
| Unit — pricing boundary | `strategy/HourlyPricingStrategyTest` — exact-hour vs. one-minute-over rounding, missing-rate rejection, negative-duration rejection |
| Unit — allocation policy | `strategy/NearestAvailableSpotAllocationStrategyTest` — smallest-sufficient-size preference, nearest-floor-to-gate preference, bus-only-fits-large, no-spot-anywhere |
| Integration | `repository/InMemorySpotRepositoryTest` — reserve/free/out-of-service/restore cycles against the real index implementation |
| Integration (end-to-end) | `service/ParkingLotServiceTest` — full park→unpark happy path, flat-rate pricing, duplicate-plate rejection, re-entry after exit |
| Negative / failure-injection | `service/ParkingLotServiceTest#failedPaymentLeavesTicketActiveAndSpotOccupied` — injects an always-fail `PaymentProcessor` and asserts the ticket/spot are *unchanged*, not just that an exception was thrown |
| Boundary | `service/ParkingLotServiceTest#parkingFailsCleanlyWhenLotHasNoCompatibleSpot` — exact-capacity exhaustion |
| Concurrency | `model/ParkingSpotTest#exactlyOneThreadWinsConcurrentReservation`, `repository/InMemorySpotRepositoryTest#concurrentReservationsAcrossManyThreadsNeverDoubleAllocateASpot`, `service/ParkingLotServiceTest#manyEntryGatesCannotOverbookOrDoubleAllocateSpotsConcurrently` — all three use a `CountDownLatch`-synchronized start across dozens of threads and assert exact-uniqueness/exact-count outcomes, not just absence of exceptions |
| Admin operations | `service/AdminServiceTest` — cannot disable an occupied spot, cannot restore a spot that isn't out of service, unknown spot id is rejected |

**Why these tests would actually fail against a buggy implementation** (the
negative-control principle): the concurrency tests assert the *exact* count
of successful allocations equals the *exact* number of available spots —
a non-atomic "check `isAvailable()` then set status" implementation would,
under real thread contention, intermittently let two threads both succeed
for one spot, which these tests would catch as a count mismatch, not merely
a flaky timing issue.

**Running the tests:**
```bash
javac -d out -cp junit-platform-console-standalone.jar $(find src tests -name '*.java')
java -jar junit-platform-console-standalone.jar --class-path out --scan-class-path
```
(40 tests, all passing — see the final verification note at the end of
this exercise.)

---

## 16. Trade-offs and Alternatives

| Decision | Chosen approach | Alternative | Pros/cons | When the alternative wins |
|---|---|---|---|---|
| Spot concurrency | Lock-free CAS per spot + per-bucket skip-list index | A single `synchronized` method per floor (or one big lock) | Chosen: scales across gates, no deadlock risk, more code to reason about up front. Alternative: trivially easy to write and reason about locally, but serializes unrelated allocations | If a garage needed a cross-spot atomic operation (e.g. reserving 2 adjacent spots for an oversized vehicle+trailer as one unit), per-spot CAS alone can't express that atomically and a coarser lock (or a saga/compensation protocol) would be needed |
| Currency type | `Money` wrapping `BigDecimal` | `long` cents | Chosen: readable, avoids float rounding, `BigDecimal` arithmetic is "obviously correct" to a reviewer. Alternative: faster, no object allocation per operation, but requires careful cents↔dollars conversion everywhere | At extreme throughput (millions of fee calculations per second) `long` cents measurably reduces GC pressure — not a concern at garage scale |
| Exceptions | Unchecked, domain-specific (`ParkingLotException` subclasses) | Checked exceptions | Chosen: callers aren't forced to catch/declare for every expected outcome, keeps strategy interfaces clean (`Optional` for "normal" absence, exceptions for genuine failures). Alternative: the compiler forces every caller to handle every failure mode explicitly | In a library with many independent external callers (not one cohesive codebase), checked exceptions make the required handling more discoverable at compile time |
| Vehicle type modeling | Enum + `EnumSet`-backed compatibility table | Class hierarchy (`Vehicle` abstract, `Car`/`Motorcycle`/`Bus` subclasses) | Chosen: the compatibility rule is a closed, static table with no per-type *behavior* beyond "can this fit here" — an enum expresses that directly. Alternative: lets each vehicle type carry independent behavior/fields, at the cost of needing `instanceof`/visitor-style dispatch for the fit rule | If vehicle types later needed genuinely different *behavior* (not just different static properties) — e.g. a bus requiring a different unloading procedure at exit — a class hierarchy would pay for itself |
| Allocation fairness | First-successful-CAS-wins (no ordering guarantee across gates) | A ticket-queue/fair-lock per bucket | Chosen: maximizes throughput, matches the physical reality (whichever gate's barrier opens first gets there first). Alternative: guarantees FIFO order across competing gates, at the cost of a queue and the possibility of one slow thread blocking others behind it | If the business requirement were literally "process gate requests in strict arrival order" (e.g. a ticketed queue system), a fair lock/queue becomes necessary |
| Persistence | In-memory | A real RDBMS (Section 12) | Chosen: correct scope for this exercise, zero incidental complexity. Alternative: durability across restarts, multi-process sharing | The moment "survive a restart" or "shared across more than one process" becomes a real requirement (Section 17) |

---

## 17. Production Evolution

Kept explicitly separate from the core interview-scoped solution above.

- **Persistence:** swap `InMemory*Repository` for implementations backed
  by the schema in Section 12; `SpotRepository`/`TicketRepository`/
  `PaymentRepository` are the exact seam this requires — no other class
  changes.
- **Distributed coordination:** only needed the moment more than one
  *process* can allocate spots in the same garage (e.g. horizontally
  scaled API servers in front of one shared DB). At that point the
  in-memory CAS becomes a DB-level optimistic-lock `UPDATE` (Section 12);
  for a true multi-process, high-contention single bucket, a distributed
  lock (e.g. Redis `SETNX` with TTL) or a single-writer queue per
  (floor,size) bucket avoids thrashing on DB row contention.
- **Monitoring/metrics:** occupancy-per-floor/size (already exposed via
  `AdminService.occupancySnapshot()`) exported as gauges; allocation
  latency and rejection rate as histograms/counters; payment success/
  failure rate per processor, alertable if it spikes.
- **Logging:** structured audit log of every state transition (who/what/
  when) for both compliance (payment records) and debugging concurrency
  issues in production.
- **Fault tolerance for payment:** idempotency keys per ticket for the
  real payment-gateway call (Section 13's explicit gap), with retry +
  exponential backoff on transient network failures, and a reconciliation
  job that detects "charged at the gateway but our DB transaction didn't
  commit" (the classic dual-write problem) via an outbox table.
- **Operational concerns:** admin operations need real authN/authZ (out of
  scope here, assumed trusted staff), rate limiting on entry gates to
  prevent a malfunctioning gate from hammering the allocator, and a
  circuit breaker around the payment gateway call.
- **Multi-garage:** a thin routing layer in front of N independent
  `ParkingLotService` instances (one per physical garage) — deliberately
  *not* one shared distributed allocator across garages, since garages
  don't share spots; this is embarrassingly parallel, not a distributed
  systems problem.
- **API evolution / backward compatibility:** `SpotAllocationStrategy`/
  `PricingStrategy`/`PaymentProcessor` are already designed as narrow,
  single-method interfaces specifically so a new implementation can be
  added without a breaking change to any existing caller — this is the
  concrete payoff of Section 7's pattern choices, not a hypothetical one.
