# Follow-Up Questions — Parking Lot

Realistic interviewer follow-ups, categorized, each with a full answer.
No repetition across categories or with `STAFF_LEVEL_DEEP_DIVE.md` — this
file covers broader OOD/SOLID/pattern/Java-fundamentals ground; the deep
dive covers problem-specific architectural scrutiny.

---

## Object-Oriented Design

**1. Why is `ParkingSpot` a class with guarded methods instead of a simple
data holder (getters/setters) with validation in the service layer?**
Because the state machine (Section 6 of README) is a property of the spot
itself, not of whoever happens to be calling it. If validation lived in
`ParkingLotService` instead, every future caller of `ParkingSpot` (a new
service, a test, a future admin tool) would have to re-implement the same
"only transition from X to Y" checks correctly, and any one of them could
get it wrong. Putting the guard on the object (`tryReserve`, `occupy`,
`vacate`) makes illegal states genuinely unrepresentable through the
public API — this is encapsulation doing real work, not just hiding
fields.

**2. Why does `Vehicle` carry a `VehicleType` field instead of being an
abstract class with `Car`/`Motorcycle`/`Bus` subclasses?**
Covered in depth in README Section 4/16: the type only drives a static
compatibility lookup (`SpotSize.canFit`), not independent per-type
behavior. A subclass hierarchy would need `instanceof` or visitor dispatch
to answer "can this vehicle fit here," which is strictly more code and
more indirection than an enum + `EnumSet` table for a fixed, closed set of
types with no behavioral differences.

**3. Is `Gate` a good candidate for inheritance (`EntryGate extends Gate`,
`ExitGate extends Gate`)?**
It was considered and rejected in favor of a `Kind` enum field. The
difference between an entry and exit gate is purely which operations are
called on it externally (`EntryGateService` vs `ExitGateService` wrap it
differently) — `Gate` itself has no behavior that differs by kind, so an
inheritance hierarchy would add two classes with zero actual behavioral
divergence. The `Kind` enum captures the distinction for anyone who needs
to branch on it (e.g. a reporting tool) without the ceremony of subclasses.

**4. What's the relationship between `ParkingLotService` and
`EntryGateService`/`ExitGateService` — composition or inheritance?**
Composition: each gate service holds a reference to the shared
`ParkingLotService` and delegates to it. Inheritance would be wrong here —
an `EntryGateService` is not a kind of `ParkingLotService`, it *uses* one,
and multiple gate services share a single `ParkingLotService` instance,
which inheritance can't express (you can't have multiple instances of one
class "inherit" a single shared instance's state).

**5. Why does `Ticket` hold a reference to the whole `ParkingSpot` object
rather than just a spot id string?**
Both were considered. Holding the object directly lets
`ParkingLotService.unparkVehicle` call `spotRepository.freeSpot(ticket.getSpot())`
without a repository lookup, which is simpler and avoids an extra
map lookup on the hot exit path. The trade-off: it means `Ticket` is
coupled to the in-memory object graph's lifetime — if this were persisted
and deserialized independently of the live `ParkingSpot` objects (Section
12), the persisted `Ticket` row would store a `spot_id` foreign key instead,
and the in-memory `Ticket`'s direct object reference is really a
convenience specific to the in-memory implementation, not a universal
property of the domain model.

---

## SOLID Principles

**6. Where does Single Responsibility actually show up as a concrete
design decision, not just a buzzword?**
`AdminService` vs `ParkingLotService` (README Section 7/`STAFF_LEVEL_DEEP_DIVE`
Q17) — operational concerns and core park/unpark flow are split because
they change for different reasons and need different authorization models
in production. Also `PaymentService` vs `ParkingLotService` — "which
payment rail to use" is a separate responsibility from "sequence the
park/unpark steps correctly," so charging logic lives in its own class
rather than being inlined into the facade.

**7. Where does Open/Closed actually get exercised, concretely?**
Adding `FlatRatePricingStrategy` alongside `HourlyPricingStrategy` required
zero changes to `ParkingLotService`, `Ticket`, or any repository — only a
new class implementing `PricingStrategy` and a different constructor
argument at wiring time. The test suite proves this: `flatRatePricingIgnoresDuration`
in `ParkingLotServiceTest` swaps the strategy in with no other code change.

**8. Where might Liskov Substitution be at risk in this design?**
`PaymentProcessor.charge()` is the interface most worth scrutinizing: all
three implementations (`CashPaymentProcessor`, `CardPaymentProcessor`,
`WalletPaymentProcessor`) must honor the same contract — "return a
`Payment` whose status reflects outcome; never throw for a normal decline."
`CardPaymentProcessor` currently fails (returns `PaymentStatus.FAILED`)
only for a non-positive amount, which is an edge case that should never
occur in practice given pricing always returns positive fees — if a future
processor implementation decided to *throw* for a declined card instead of
returning a `FAILED` status, it would violate the interface's implicit
contract and break `PaymentService.pay`'s assumption that only
infrastructure failures throw. This is worth naming as a documented
contract (the `PaymentProcessor` javadoc does state it) rather than an
accident.

**9. Where does Interface Segregation apply here?**
`SpotRepository`, `TicketRepository`, and `PaymentRepository` are three
separate interfaces rather than one monolithic `Repository` interface,
even though a single in-memory class *could* implement all three.
`NearestAvailableSpotAllocationStrategy` only needs `SpotRepository` — it
would be forced to depend on ticket/payment persistence methods it never
calls if these were merged, violating ISP for no benefit.

**10. Where does Dependency Inversion show up, and what would break
without it?**
`ParkingLotService`'s constructor takes `SpotRepository`,
`TicketRepository`, `SpotAllocationStrategy`, `PricingStrategy`, and
`PaymentService` — all abstractions, zero concrete classes. Without this,
`ParkingLotService` would have to `new InMemorySpotRepository(...)`
internally, which would make it impossible to substitute a different
repository for tests (every test would share real in-memory state across
test methods) or for a future persistent implementation without editing
`ParkingLotService`'s source.

---

## Design Patterns

**11. Is `ParkingLotBuilder` a "real" Builder pattern or just a
convenience method?**
It's the real pattern: it accumulates configuration across multiple
`withFloor()` calls before a single `build()` call produces the final
immutable `ParkingLot`, exactly matching the classic Builder intent
("separate the construction of a complex object from its representation").
The test it would need to pass to prove it's not just a convenience method:
calling `build()` twice on the same builder instance with different floor
specs added between calls should produce two independent, non-interfering
`ParkingLot` instances — which it does, since `build()` only reads the
accumulated `floorSpecs` list, it doesn't consume/clear it.

**12. Why Strategy and not Template Method for pricing?**
Template Method would mean one abstract `PricingStrategy` base class with
a fixed algorithm skeleton (e.g. "validate, then compute") and subclasses
overriding one step. This was rejected because `HourlyPricingStrategy` and
`FlatRatePricingStrategy` don't share a meaningful common skeleton beyond
"take a ticket and exit time, return a fee" — flat-rate doesn't even look
at duration. Forcing a shared base class here would be inheritance used
for code reuse rather than genuine is-a/shares-behavior modeling — Strategy
via a pure interface is the better fit when implementations are
genuinely independent algorithms, not variations on one fixed skeleton.

**13. Could the Factory pattern (as distinct from Builder) apply anywhere
else in this design?**
Yes — a `PaymentProcessorFactory` that maps `PaymentMethod` → `PaymentProcessor`
could replace the `Map<PaymentMethod, PaymentProcessor>` registry currently
constructed by hand in `ParkingLotDemo`/tests. It wasn't introduced because
the registry is a simple, static, small map built once at startup — a
factory class would add a layer of indirection with no behavior beyond
what `EnumMap.get()` already provides. If processor selection needed
runtime logic beyond a lookup (e.g. "choose based on amount, falling back
if a processor is down"), a factory *would* earn its place.

**14. Why not use the Decorator pattern for, say, adding a surge-pricing
multiplier on top of hourly pricing?**
This is actually a good future extension point, and Decorator fits well:
a `SurgePricingDecorator implements PricingStrategy` wrapping a delegate
`PricingStrategy` and multiplying its result by a time-of-day factor would
be fully additive — zero changes to `HourlyPricingStrategy` or
`ParkingLotService`. It wasn't built now because no requirement calls for
it (Section 1's out-of-scope list explicitly defers dynamic pricing) —
introducing it speculatively would be exactly the "pattern forced in to
show off" anti-pattern the README's design-patterns section explicitly
warns against (Section 7).

**15. Is there a Chain of Responsibility opportunity in the exit flow?**
Potentially, if multiple validation/discount steps needed to run in
sequence before computing a final fee (e.g. "check for a validation
voucher, then a loyalty discount, then the base rate"). The current exit
flow has exactly one fee-computation step, so a chain would be
over-engineering for a single link — this would become worth introducing
the moment a second independent "fee adjustment" concern appears.

---

## Data Structures

**16. Why `ConcurrentSkipListSet` and not `PriorityBlockingQueue` for the
available-spot index, since both give ordered access?**
`PriorityBlockingQueue` doesn't support efficient arbitrary-element removal
(`remove(Object)` is O(n) linear scan) which is required by
`markOutOfService` (remove one *specific* spot from the middle of the
pool, not just the head). `ConcurrentSkipListSet.remove(Object)` is
O(log n) because it's a genuine sorted-set structure, not a heap — a heap
only optimizes "peek/remove the minimum," not "remove this arbitrary
element," which this design needs for both operations.

**17. Why not a simple array/bitset for spot status, given spot counts are
small and bounded?**
A bitset (`AVAILABLE`/not) would need a *separate* parallel structure to
answer "which spot, and in what order" for allocation, and would lose the
rich per-spot state (`RESERVED` vs `OCCUPIED` vs `OUT_OF_SERVICE` — 4
states, not 2) without additional encoding. The marginal memory/speed win
of a bitset over `ConcurrentSkipListSet<ParkingSpot>` at garage scale
(hundreds of spots) is not worth the loss of readability and the loss of
atomic multi-bit CAS semantics `AtomicReference<SpotStatus>` already gives
for free per spot.

**18. What's the time complexity of `AdminService.occupancySnapshot()`,
and is that acceptable?**
O(total spots in the garage) — it iterates every floor and every spot once
to build the counts. This is acceptable because it's a reporting/dashboard
operation, not a hot path called per-vehicle — at garage scale (hundreds
to low-thousands of spots) this completes in microseconds, and caching it
would add staleness risk for no measurable benefit at this scale.

**19. Why `EnumMap` for rate tables instead of `HashMap`?**
`EnumMap` is backed by a plain array indexed by the enum's ordinal
internally, giving O(1) lookup with no hashing/bucketing overhead and a
smaller memory footprint than `HashMap`'s node-based buckets — a strict
upgrade for any map keyed by a small, fixed enum, with zero downside (it
still implements the full `Map` interface).

**20. If spot counts grew to tens of millions across a network of
garages, would `ConcurrentSkipListSet` per bucket still be appropriate?**
No, and this is explicitly discussed in `STAFF_LEVEL_DEEP_DIVE.md` Q5 —
the correct response to that scale isn't a different data structure for
one bucket, it's recognizing the contention domain should be sharded by
garage (each garage already gets its own `SpotRepository` instance in this
design), not by growing one data structure to planetary scale.

---

## Java Internals

**21. Why does `Money.equals()` use `BigDecimal.compareTo() == 0` instead
of `BigDecimal.equals()`?**
`BigDecimal.equals()` considers scale as part of equality —
`new BigDecimal("2.50").equals(new BigDecimal("2.5"))` is `false`, because
they have different scales (2 vs 1), even though they represent the same
numeric value. For a `Money` value object, two amounts that are numerically
equal must be considered equal regardless of how many trailing zeros were
in the literal that produced them — `compareTo() == 0` compares numeric
value only, which is the correct equality semantics here. (Note:
`Money.hashCode()` correspondingly uses `stripTrailingZeros()` to stay
consistent with this equals definition, satisfying the equals/hashCode
contract.)

**22. Why is `ParkingSpot.currentVehicle` marked `volatile` even though
the design argument says a happens-before edge already exists through the
`ConcurrentHashMap`?**
Defensive correctness over relying on a subtle, easy-to-break-by-refactor
happens-before argument. The README's concurrency section (11.3) explains
the *current* correctness argument doesn't strictly require `volatile`
given today's exact call sequence, but marking it `volatile` costs nothing
(reads/writes of object references are not the expensive part of volatile;
the expensive part is preventing certain compiler/CPU reorderings, which is
cheap for a field written once per occupy/vacate cycle) and makes the class
correct even if a future refactor changes how a `ParkingSpot` reference is
published to another thread, without requiring the reader to re-derive the
happens-before argument from scratch.

**23. Does `AtomicReference<SpotStatus>.compareAndSet` ever produce the
ABA problem here, and does it matter?**
Technically yes, in the abstract: `AVAILABLE → RESERVED → AVAILABLE`
(reserve then release) returns the reference to its original value, and a
thread that read `AVAILABLE`, got preempted, then woke up and CAS'd
assuming nothing changed, cannot distinguish "never changed" from
"changed and changed back." In practice it does not matter here because
every CAS in this design is paired with a *specific intended transition*
(e.g. `compareAndSet(AVAILABLE, RESERVED)`) — the CAS succeeding means
"the spot was `AVAILABLE` at the moment of this atomic check," and the
calling code's logic is correct regardless of *how many times* it cycled
through `AVAILABLE` in between, because no code path here depends on
detecting "did this change" versus "is it currently in state X" — it's
the latter that's being tested, which CAS answers correctly even under ABA.

**24. Why does `IdGenerator` use `AtomicLong` instead of a `synchronized`
counter method?**
`AtomicLong.incrementAndGet()` compiles down to a hardware CAS loop (or a
single `LOCK XADD` instruction on x86) with no OS-level lock/monitor
acquisition, meaning no thread ever blocks/parks waiting for this
operation — contrast with `synchronized`, which under contention can
involve the JVM inflating a lock to a heavyweight monitor with OS-level
blocking. For a counter incremented on every single ticket/payment across
every gate, this is a meaningfully hotter path than, say,
`markSpotOutOfService` (an infrequent admin action), so the lock-free
primitive is the right default here, not just a stylistic consistency
choice.

---

## Multithreading

**25. If you replaced `AtomicReference<SpotStatus>` with a plain field
guarded by `synchronized(this)` on every `ParkingSpot` method, would the
system still be correct?**
Yes, correctness would be preserved — `synchronized` gives the same
mutual-exclusion guarantee, just via blocking rather than a lock-free CAS
loop. The difference is purely performance/liveness characteristics under
contention (Section 11.2/16 of README and Q22 of the deep dive discuss
this trade-off at length), not correctness. This is a useful thing to say
out loud in an interview: lock-free isn't "more correct" than
lock-based, it's a different point on the same correctness guarantee with
different performance properties.

**26. What's the actual failure mode if `ParkingSpot.occupy()` were called
without first calling `tryReserve()` successfully?**
It throws `IllegalStateException` immediately — `occupy()`'s CAS is
`compareAndSet(RESERVED, OCCUPIED)`, and if the spot is still `AVAILABLE`
(never reserved), that CAS fails and the method's explicit check throws.
This is intentional defensive programming: the method doesn't silently
no-op or silently force the transition, because either of those would mask
a genuine caller bug (skipping the reservation step) rather than surfacing
it loudly and immediately.

**27. Could `parkVehicle` and `unparkVehicle` running concurrently for
*different* vehicles on *different* spots ever interfere with each other?**
No — they touch entirely disjoint shared state (different `ParkingSpot`
instances, different entries in the plate-claim map), and every shared
structure they do both touch (`ConcurrentHashMap`-backed indices) is
internally thread-safe for concurrent operations on different keys. There
is no shared counter, shared lock, or shared mutable field that both
operations contend on when operating on unrelated vehicles/spots — this is
precisely why the per-spot, per-bucket granularity (rather than one
lot-wide lock) was chosen.

**28. How would you test for a deadlock in this system if you suspected
one, given there are no explicit locks?**
I'd first note that since there are zero `synchronized` blocks and zero
`Lock.lock()` calls anywhere in the source, a classical deadlock (two
threads each holding a lock the other needs) is structurally impossible —
there's nothing to hold. If a suspected "hang" were reported, I'd look
instead for a livelock (two CAS-retry loops perpetually undoing each
other's progress) or simply a slow/blocking I/O call masquerading as a
hang (e.g. a real payment gateway call with no timeout) — a thread dump
would immediately distinguish these: threads blocked on a monitor (would
show as `BLOCKED` in the dump, and wouldn't appear here at all given no
locks exist) versus threads spinning in a CAS retry loop (would show as
`RUNNABLE`, repeatedly, across consecutive dumps) versus threads parked on
network I/O (would show as `WAITING`/`TIMED_WAITING` on a socket read).

---

## Database and Persistence

**29. If you moved to a real database, would you keep the CAS-style
optimistic concurrency, or switch to row-level pessimistic locking
(`SELECT ... FOR UPDATE`)?**
Keep optimistic, via the `version` column approach sketched in README
Section 12 — this most directly mirrors the in-memory CAS semantics
(an `UPDATE ... WHERE status = 'AVAILABLE' AND version = ?` either affects
one row or zero, exactly like `compareAndSet` either succeeds or fails) and
avoids holding row locks across what could be, in a real system, a slower
network round-trip to the application layer and back. `SELECT ... FOR
UPDATE` would hold a row lock for the duration of the transaction, which
under high contention on a popular bucket could serialize gates behind
each other at the database layer — reintroducing exactly the contention
the in-memory design was built to avoid.

**30. How would `tryClaimPlate`'s atomicity be preserved in a database
implementation?**
Via the partial unique index sketched in Section 12
(`CREATE UNIQUE INDEX ... WHERE status = 'ACTIVE'`) — inserting a new
ticket row for a plate that already has an `ACTIVE` ticket would violate
that constraint and the database rejects the `INSERT` atomically, exactly
mirroring `ConcurrentHashMap.putIfAbsent`'s atomic test-and-set, just
enforced by the database's own index machinery instead of in-process
memory.

**31. What isolation level would you actually need for the exit flow's
transaction (payment commit + ticket close + spot free), and why not
`SERIALIZABLE` by default?**
`READ COMMITTED` is sufficient, because every write in that transaction
targets rows identified by a specific primary key the caller already
holds (this specific `ticketId`, this specific `spotId`) — there's no
range query or multi-row aggregate being computed that `SERIALIZABLE`
would be needed to protect against phantom reads for. `SERIALIZABLE`
would add real throughput cost (more aborts/retries under load) for a
consistency guarantee this specific transaction shape doesn't need —
over-specifying isolation level is a common and avoidable performance
mistake.

---

## Performance

**32. What's the Big-O of parking N vehicles sequentially versus
concurrently, and does concurrency actually help here?**
Sequentially: O(N log n) total (N allocations, each O(log n) against its
bucket). Concurrently with K threads and no contention (different
buckets): the wall-clock time approaches O((N/K) log n) — genuine
speedup. Under heavy contention (all K threads hitting the same bucket):
each CAS retry loop adds overhead, but because the critical section
(`pollFirst`+CAS) is extremely short, even full contention on one bucket
still vastly outperforms a coarse-grained lock held for the same
operation, because threads spend their "waiting" time retrying a cheap
CAS rather than being parked/unparked by the OS scheduler (which has
real, measurable overhead per context switch).

**33. Where would you actually put a cache, if anywhere, in this design?**
I would not add a cache to the allocation path — the `ConcurrentSkipListSet`
index already *is* the fast path, and caching "available spots" on top of
it would just be a second, harder-to-keep-consistent copy of the same
data. The one place a cache genuinely earns its keep: `AdminService.occupancySnapshot()`
if it were called very frequently (e.g. a live dashboard polling every
second across a garage with tens of thousands of spots) — a short-TTL
cache (a few hundred milliseconds) would trade negligible staleness for
avoiding repeated full-garage scans, which is a defensible trade given
occupancy reporting doesn't need to be instant-accurate to the millisecond.

---

## Failure Handling

**34. If `PaymentProcessor.charge()` throws an unexpected
`RuntimeException` (not a normal decline) instead of returning a `FAILED`
payment, what happens, and is that acceptable?**
`PaymentService.pay()` does not catch it, so it propagates out of
`unparkVehicle` uncaught by this layer — the ticket remains `ACTIVE`, the
spot remains `OCCUPIED` (invariant I-6 still holds, since none of
`Ticket.close`/`freeSpot`/`releaseClaim` ran before the exception). This is
acceptable *as a correctness matter* (no corruption) but not acceptable as
an *operational* matter — an uncaught exception reaching whatever called
`ExitGateService.processExit` (e.g. a web controller) needs to be turned
into a clear, actionable error for the driver/operator ("payment system
unavailable, please try again" or route to a different payment method)
rather than a raw stack trace — this is exactly the kind of
production-hardening concern flagged as deliberately out of scope for the
core interview-level solution (README Section 17) but worth naming when
asked directly.

**35. What happens if two admin staff members call `markSpotOutOfService`
on the same spot at the exact same time?**
Exactly one succeeds (its `bucket.remove(spot)` call wins the race to
remove the element from the shared set) and the other's `remove` call
returns `false`, causing `AdminService.markSpotOutOfService` to throw
`InvalidSpotOperationException` reporting the spot is no longer in a
markable state — this is the correct, honest outcome (the second staff
member is told "someone/something already acted on this spot") rather
than silently succeeding twice or corrupting the index.

---

## Production Architecture

**36. How would this design change if gates needed to work correctly even
when the central service is briefly unreachable (network partition between
gate hardware and the server)?**
This moves the problem from "concurrency within one process" to
"distributed systems with partial availability," which is explicitly
flagged as a production-evolution concern, not part of the core design
(README Section 1/17). The realistic mitigation: gates would need a local,
degraded-mode fallback (e.g. "open the barrier and print a generic ticket
with just a timestamp, reconcile against the central system once
connectivity returns") rather than refusing entry entirely — but this
fundamentally changes the consistency model (you can no longer guarantee
the "no spot double-allocated" invariant in real time during a partition,
only eventually, after reconciliation), which is a materially different
and harder problem than anything in this design's current scope.

**37. How would you introduce blue-green or canary deployment for this
service without losing in-flight parked vehicles' state?**
This is only solvable once persistence exists (Section 12) — with
persistent storage, a new version of the service can simply read the same
database the old version was writing to, so in-flight tickets/spot states
survive a deploy that swaps which process is serving traffic. With the
current in-memory design, any deploy is necessarily a full state loss
(every parked vehicle's ticket vanishes from the system's point of view,
Question 7 of the deep dive covers this same gap from a different angle) —
this is one of the clearest, most concrete illustrations of why
"in-memory is fine for this exercise, but the single biggest thing
standing between this design and a real production deployment is
persistence" (README Section 12/17) is not a hedge, it's the literal
truth.
