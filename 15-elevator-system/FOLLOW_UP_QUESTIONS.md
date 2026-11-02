# Follow-Up Questions — Elevator System

27 follow-up questions across the ten mandated categories, each with a
complete answer grounded in this codebase's actual classes and decisions
(not generic textbook answers). No question is repeated across categories.

---

## Object-Oriented Design

### 1. Why does `Elevator` not implement `ElevatorState` directly, using an enum + switch instead of a separate class hierarchy?

Because the State pattern's entire value is letting each state *own* its
transition logic as independently testable, independently extensible code,
rather than centralizing all five states' behavior into one giant `switch`
inside `Elevator.tick()`. With a switch, adding a sixth state (say,
`EMERGENCY_STOP`) means editing the one method that already handles five
other states' logic — high risk of an accidental cross-state bug. With the
interface approach, adding `EmergencyStopState` is a brand-new file that
touches nothing else. The tests confirm this independence —
`ElevatorStateMachineTest` tests each state's `step()` behavior in near
isolation.

### 2. Why is `Door` a separate class instead of fields directly on `Elevator`?

Single Responsibility: `Elevator` already owns floor position, direction,
state-machine delegation, and stop queues — folding door-open/hold/
close/obstruction-retry logic (its own 5-state lifecycle, its own retry
counter, its own jam-detection threshold) directly into `Elevator` would
make one class responsible for two independently-evolving concerns. Keeping
`Door` separate also makes it independently unit-testable without needing
a full `Elevator` (see `DoorTest`, which constructs a bare `Door` with a
mock `ObstructionSensor` and never touches `Elevator` at all).

### 3. `HallRequest` and `CabinRequest` both implement `Request`, but `Request` has no behavior beyond field accessors — isn't this just organizing data, not actual object-oriented behavior?

Yes, deliberately — these are **value objects**, not entities, and modeling
them as behaviorless data-carriers is the correct OO choice *for value
objects specifically*. The OO principle being applied isn't "give every
class behavior," it's "model what has identity and behavior as a class
with behavior, and model what's pure data as pure data" — conflating the
two (e.g., giving `HallRequest` a `dispatch()` method that reaches into the
controller) would violate separation of concerns far more than the current
"thin" design does. The behavior that varies — how to respond to a request
— lives correctly in `SchedulingStrategy`, not on the request itself.

### 4. Why does `ElevatorController` expose `snapshot()` returning a `List<ElevatorSnapshot>` rather than just exposing `List<Elevator>` directly?

Encapsulation of mutability: `Elevator` is a live, mutable entity whose
state changes every `tick()`; handing out live references would let external
callers (e.g., a UI layer) hold onto an `Elevator` reference and either
accidentally call a mutating method meant only for internal use, or read
its fields at an unpredictable later time expecting them to reflect "now."
`ElevatorSnapshot` is an immutable record capturing field values at the
moment of the call — the reader gets a safe, frozen view with no risk of
misusing it as a live handle.

## SOLID Principles

### 5. Where does Open/Closed actually pay off in this codebase, concretely — not just "Strategy pattern is OCP," show the actual change that doesn't require modifying existing classes?

Adding `NaiveFcfsStrategy` as a second, fully independent
`SchedulingStrategy` implementation required zero changes to
`ElevatorController`, `Elevator`, or any state class — `ElevatorController`
depends only on the `SchedulingStrategy` interface and is constructed with
whichever implementation the caller chooses (`setStrategy()` even allows
runtime swapping, exercised directly in
`ElevatorSystemDemo.strategyComparisonDemo`). This is the textbook OCP
payoff: the set of dispatch algorithms is open for extension (add a third
strategy any time) while the controller class is closed for modification
(never needs to change to support it).

### 6. Does `ElevatorState` actually satisfy Liskov Substitution — can any state be substituted for any other without breaking `Elevator.tick()`'s correctness?

Yes, by construction: `Elevator.tick()` only ever calls
`state.step(this)` and then potentially reassigns `this.state` to whatever
the state's `step()` method decides — it never inspects *which* concrete
state it's holding (no `instanceof` checks, no casting). Every
`ElevatorState` implementation satisfies the same contract: read the
elevator's current data, decide the next action, return the next state
(possibly `this`). Any implementation satisfying that contract is a valid
substitute, which is exactly LSP — the caller's correctness never depends
on the specific subtype.

### 7. Does `NearestCarScanStrategy`'s constructor taking a raw `int` for `fairnessThreshold` violate Interface Segregation in any way? Why or why not?

No — ISP is about not forcing implementers/clients to depend on methods
they don't use, which is an interface-shape concern, not a constructor
primitive-parameter concern. `SchedulingStrategy` itself has exactly one
method (`selectElevator`), which is already about as narrow as an
interface can be — no client of `SchedulingStrategy` is forced to
implement unrelated methods. The constructor parameter question is a
separate (valid, but different) discussion about constructor telescoping /
builder usage, not ISP.

### 8. `ElevatorController`'s constructor depends on a concrete `List<Elevator>`, not an abstraction like `Supplier<List<Elevator>>` — does this violate Dependency Inversion?

No — DIP is about depending on abstractions for things that vary in
*implementation*, not about abstracting every single parameter type.
`List<Elevator>` here is a plain data container, not a policy that varies
— there's no meaningful alternative "elevator list provider" strategy to
abstract over. DIP *is* correctly applied where it matters: the
*scheduling policy* varies (there are two real implementations, more
likely to come) and is correctly injected as the `SchedulingStrategy`
abstraction. Abstracting the list-of-elevators parameter as well would be
needless indirection with no corresponding variation point to justify it.

## Design Patterns

### 9. You used State and Strategy — both look structurally similar (an interface with a context holding a reference to an implementation). What's the actual distinguishing design intent between how you used each here?

`ElevatorState` instances are swapped *by the context itself*, as a direct
consequence of the context's own internal logic reacting to its own data
(`Elevator.tick()` reassigns `this.state` based on what `step()` returns)
— the context drives its own transitions. `SchedulingStrategy` is swapped
*externally*, by a caller choosing an algorithm up front (or via
`setStrategy()`), and the strategy itself never causes the controller to
switch to a different strategy — there's no "scheduling decision leads to
changing the scheduling algorithm" feedback loop. That's the real
distinction: State models an object whose *own* behavior changes over
*its own* lifecycle; Strategy models a swappable *algorithm* chosen by
something external to the algorithm itself.

### 10. Why not use the Observer pattern to notify something when a car arrives at a floor (e.g., for a UI or logging system)?

Deliberately omitted (README §7's explicit "not used" list) because no
current requirement needs it — adding Observer infrastructure (a
`listener` list on `Elevator`, notification dispatch on every state
transition) for zero current consumers is speculative generality, which
the spec explicitly warns against over-engineering. If a future
requirement needs real-time arrival notifications (e.g., floor indicator
displays), this is the single most natural place to introduce Observer —
and `ElevatorSnapshot` already demonstrates the "immutable data to hand to
an outside listener" pattern that would plug straight into it.

### 11. Why didn't you use a Factory for constructing `Elevator` instances?

Because `Elevator` construction has no polymorphic variation and no
complex conditional assembly logic to hide — it's one concrete class with
a straightforward constructor (`id`, floor bounds, capacity, obstruction
sensor). A Factory earns its keep when construction involves choosing
*among* multiple concrete types, or hides genuinely complex setup/wiring.
Here, `new Elevator(...)` directly is clearer than a Factory wrapping a
single `new` call — introducing one would be indirection without payoff
(this exact reasoning is also called out in the Interview Playbook §9 as
a ready answer if asked).

### 12. Could the Builder pattern have improved `Elevator`'s or `ElevatorController`'s constructors?

`Elevator`'s constructor has 4-5 required, non-optional parameters
(id, floor bounds, capacity, sensor) with no valid partial-construction
state and no optional parameters to default — Builder adds value chiefly
when there are many optional parameters or multi-step valid partial
states, neither of which applies here. `ElevatorController` similarly
takes exactly two required collaborators. If a future version added many
optional tuning knobs (fairness threshold, load penalty weight, per-car
capacity overrides, etc. all configurable at construction), Builder would
become the right call — worth naming as a likely refactor trigger, not a
current gap.

## Data Structures

### 13. Why `ConcurrentSkipListSet` for the per-car stop queues instead of a `PriorityQueue`?

`PriorityQueue` only efficiently exposes the single smallest (or largest,
with a comparator) element — it cannot efficiently answer "give me the
smallest element *greater than X*" (needed for "next stop above my current
floor"), nor can it iterate in sorted order without destructively polling.
`TreeSet`/`ConcurrentSkipListSet` expose `higher()`/`lower()`/`ceiling()`/
`floor()` in O(log n), which is exactly the query the SCAN algorithm needs
("what's my next stop in this direction from here") — and it needs this
non-destructively, since the set is also being iterated/inspected for
`pendingStopCount()` without removing anything. `PriorityQueue` is the
wrong structure for this access pattern regardless of concurrency.

### 14. Why a `Set` (no duplicates) instead of a `List` or multiset for stops — doesn't that lose information about "two different passengers want floor 7"?

Deliberately, because at the level this system operates, a stop is a
physical event — "the car visits floor 7 and opens its door" — and that
event is identical in cost and effect whether one or five passengers
requested it. A multiset would let duplicate floor-7 entries pile up, but
the *marginal* stop would still only visit floor 7 once — the extra
entries would do nothing except consume memory and complicate the
removal logic (`remove()` would need to decide how many copies to clear).
If passenger *count* at a stop mattered for a feature (e.g., capacity
enforcement), that's tracked separately via `Elevator.occupancy()`
(an `AtomicInteger`), not by duplicating floor entries.

### 15. What's the complexity of `Elevator.pendingStopCount()`, and could it be made O(1)?

Currently O(1) amortized for each `ConcurrentSkipListSet.size()` call,
since `ConcurrentSkipListSet` (backed by `ConcurrentSkipListMap`) maintains
no cached size and documents its `size()` as requiring a full traversal in
the general case — so `pendingStopCount()` is actually **O(n)** where n is
the number of pending stops in that one car (small in practice — rarely
more than a handful of floors — but worth naming precisely rather than
assuming `size()` is free, a common misconception). It *could* be made
true O(1) by maintaining a separate `AtomicInteger` counter incremented/
decremented alongside every `add`/`remove` — not done here because the
sets are small enough that the real-world cost is negligible, and the
dual-bookkeeping approach introduces its own bug surface (counter drifting
out of sync with the actual set contents under concurrent access) for a
constant-factor gain that doesn't matter at this n.

## Java Internals

### 16. Why are `Elevator`'s `currentFloor` and `state` fields `volatile` instead of just relying on `synchronized tick()` alone?

`synchronized` on `tick()` provides mutual exclusion for *writers* (only
one thread executes the method body at a time) and also provides
visibility guarantees for threads that *also* synchronize on the same
lock — but reader threads (the scheduler evaluating cost functions, demo
code printing fleet status) read `currentFloor()`/`direction()` *without*
acquiring that lock, by design (acquiring a lock just to read a field for
a best-effort cost estimate would add contention for no correctness
benefit, since the read is inherently about a point-in-time snapshot
anyway). Without `volatile`, those un-synchronized reads would have no
guarantee of ever observing a write made inside the synchronized block —
the JMM permits an unsynchronized reader to cache a stale value
indefinitely. `volatile` restores the needed happens-before guarantee for
those specific unsynchronized reads, which is the actual reason both
mechanisms are present together rather than either alone.

### 17. Why are the State and SchedulingStrategy implementations (e.g., `IdleState.INSTANCE`) implemented as stateless singletons rather than instantiated fresh each transition?

Because every `ElevatorState` implementation here is genuinely stateless —
all the data they operate on lives on the `Elevator` parameter passed into
`step()`, never on the state object itself. Given that, allocating a new
`MovingUpState` object on every single tick transition would be pure
garbage-collector pressure with zero behavioral benefit — a classic
Flyweight-adjacent optimization enabled specifically *because* the objects
are stateless. If a future state needed per-instance data (e.g., a
`DoorOpenState` that tracked how long *this specific* door-open episode
has lasted, rather than delegating that to `Door` as currently done),
singleton-ness would need to be revisited.

### 18. `HallRequest` and `CabinRequest` are Java records — what do you get "for free" from that choice, specifically, versus a hand-written immutable class?

Automatically generated: a canonical constructor, `equals()`/`hashCode()`
based on all components (critical here since `upStops`/`downStops` are
`Set<Integer>` not `Set<HallRequest>`, but `HallRequest` equality still
matters for test assertions comparing requests), a readable `toString()`
(used directly in demo output and test failure messages), and
componentaccessors (`floor()`, `direction()`) without boilerplate. The
compact constructor is also used deliberately in `HallRequest` to validate
`direction != IDLE` at construction time — records support validation
logic in the compact constructor just like a hand-written class's
constructor would, so no capability is lost, only boilerplate is removed.

## Multithreading

### 19. Why is `Elevator.tick()` the unit of synchronization, rather than synchronizing individually around `upStops`/`downStops` mutations?

Because `tick()`'s correctness depends on several fields being read and
acted upon *together, consistently* within one logical step (current
floor, state, and the relevant stop set) — synchronizing only the
individual collection operations would still allow a scheduler thread to
`addStop()` *between* two reads inside the same `tick()` call, which is
fine (the queues are safe for that), but synchronizing *only* at that
granularity wouldn't protect the actual property that matters here:
exactly one thread advancing this car's simulated state per logical tick.
The stop queues' own thread-safety (via `ConcurrentSkipListSet`) is a
*different, complementary* guarantee from the single-writer-per-tick
guarantee that `synchronized tick()` provides — they protect different
things and both are needed.

### 20. Describe the actual concurrency test (`ElevatorControllerConcurrencyTest`) — what race would it have caught if the stop queues were plain `TreeSet`s instead of `ConcurrentSkipListSet`s?

The test launches one thread per distinct floor (sized to match exactly,
after the earlier thread-pool-deadlock fix — see Technical Details),
each calling `requestHallCall` simultaneously after a `CountDownLatch`
release, then asserts that every single requested floor eventually
appears as visited across the fleet. With plain `TreeSet`s (not thread-
safe for concurrent modification), simultaneous `add()` calls from
multiple threads on the same underlying red-black tree risk either a
`ConcurrentModificationException` thrown during the scheduler's own
iteration-based cost evaluation, or, worse, silent internal tree
corruption (lost nodes, broken invariants) with no exception at all —
intermittent, hard-to-reproduce failures that could easily pass on a
given run and fail on another. `ConcurrentSkipListSet` eliminates this
entire class of flakiness by design, which is exactly why the test is
written to run with enough concurrent threads to make the race actually
likely to manifest if the guard were ever regressed.

### 21. Why does `ElevatorSystemDemo`'s concurrent-dispatch scenario size its thread pool to exactly match the number of submitted tasks, with a comment explaining why?

Because a smaller fixed pool combined with a ready/go double-`CountDownLatch`
pattern (each worker counts down "ready," then blocks on a shared "go"
latch before all workers proceed together) deadlocks if pool size < task
count: the limited worker threads all occupy themselves blocking on "go,"
while the remaining not-yet-started tasks sit queued and never get a
thread to run on, so "ready" (which needs *every* task to have started and
counted down) never reaches zero, and the main thread blocks forever on
`ready.await()`. This was an actual bug found and fixed during
development (see Technical Details in session history) — sizing the pool
to the task count removes the possibility entirely, and the comment left
in the code is there specifically so a future contributor adding more
concurrent tasks doesn't reintroduce the same deadlock by shrinking the
pool back down.

## Database and Persistence

### 22. This system has no database at all — justify that as a deliberate design decision, not an omission.

The system's entire responsibility, as scoped (README §1/§2), is real-time
operational control of physical hardware — it needs to answer "where is
car 2 right now" and "what should car 2 do next tick," both of which are
transient, moment-to-moment facts with no inherent need to survive past
the current operational session; the actual source of truth for "where is
car 2" in a real deployment would be a physical floor sensor, not a
database row, and persisting "position" to a DB would create a stale,
second source of truth that could drift from hardware reality after a
crash (see Q5/Q6 in STAFF_LEVEL_DEEP_DIVE.md). What *would* warrant
persistence — a ride/audit log for analytics, incident investigation, or
billing — is explicitly named in README §12 as the one piece a production
system would add, and it would be an **append-only event log** (each
hall/cabin request and each arrival event, timestamped), not a mutable
table representing current state, because the durability need is "what
happened," not "what's true right now."

### 23. If you did add that append-only ride/audit log, what would the schema look like, and why avoid a normalized relational model for it?

A single wide, append-only table/stream of immutable events — e.g.,
`(event_id, event_type [HALL_CALL | CABIN_CALL | ARRIVAL | DOOR_OPEN |
DOOR_OBSTRUCTED], elevator_id, floor, direction, tick_or_timestamp)` —
intentionally denormalized, because the access pattern this serves
(analytics queries like "average wait time by floor," "how often does
obstruction trigger," "utilization per car over a week") is write-once,
read-many, time-ordered scan-heavy — exactly the shape append-only
event/log stores (or a simple timeseries-oriented table) serve well,
versus a normalized relational schema with foreign keys between an
"elevators" table and a "requests" table, which would add join overhead
for zero correctness benefit, since these events are immutable historical
facts, never updated after the fact.

## Performance

### 24. What's the per-hall-call dispatch latency, asymptotically, and is it actually a bottleneck at realistic building scale?

O(E) where E is fleet size — `NearestCarScanStrategy.selectElevator`
evaluates a constant-time cost function (a handful of volatile field
reads plus arithmetic) for every elevator and picks the minimum. For
realistic E (2-20 cars in a single building), this is a handful of
microseconds at most, nowhere near a bottleneck relative to the actual
physical timescale of elevator movement (seconds between floors) — the
dispatch decision is not the limiting factor; physical car movement
always is. (See STAFF_LEVEL_DEEP_DIVE.md Q9 for the genuinely
unrealistic-E case and why the fix there isn't "optimize this further.")

### 25. Where would you add caching, if anywhere, and where would caching actively be harmful here?

Nowhere in the dispatch hot path — every value the scheduler reads
(`currentFloor`, `direction`, `pendingStopCount`) changes every tick, so
caching any of it would either need tick-granularity invalidation (no
simpler than just reading the live volatile field, which is already O(1))
or would introduce exactly the kind of staleness bug described in
STAFF_LEVEL_DEEP_DIVE.md Q13 — caching live operational state for a
control-loop decision is actively dangerous here, not just unhelpful,
because a stale cached "current floor" could cause the scheduler to
dispatch based on where a car *used* to be. The one place caching is
legitimate is read-only, point-in-time reporting (`ElevatorSnapshot` is
effectively a manually-taken "cache" of one instant, used precisely
because it's explicitly *not* expected to stay fresh — see Q13).

## Failure Handling

### 26. What specific exception is thrown if a hall call targets a floor outside the building's configured range, and why a dedicated exception type rather than a generic `IllegalArgumentException`?

`InvalidFloorException` (unchecked, extending `RuntimeException`), thrown
from `ElevatorController.requestHallCall`/`requestCabinCall` when the
requested floor falls outside `[buildingMinFloor, buildingMaxFloor]`. A
dedicated type (rather than bare `IllegalArgumentException`) lets calling
code — or a future API layer translating these into HTTP error codes or
UI messages — catch and handle *this specific failure category*
distinctly from, say, `NoElevatorAvailableException` (all cars in
maintenance) or `ElevatorOutOfServiceException` (a cabin request targeting
a specific car that's under maintenance) — each represents a genuinely
different operator-facing remediation ("check your floor number" vs.
"no cars are currently available" vs. "that specific car is down"), and
collapsing them into one generic exception type would force every caller
to parse the message string to distinguish cases, which is fragile and
exactly what typed exceptions exist to avoid.

### 27. What happens if `NearestCarScanStrategy.selectElevator` is called with zero elevators available (all in maintenance) — walk through the actual code path, not just "it throws."

`ElevatorController.requestHallCall` first validates the floor, then calls
`strategy.selectElevator(request, elevators)`. Inside
`NearestCarScanStrategy`, the implementation filters to elevators where
`!isInMaintenance()` before computing any cost — if that filtered stream
is empty, the code does not fall through to a cost comparison over zero
candidates (which could NPE or return a meaningless default); it explicitly
checks for this case and throws `NoElevatorAvailableException` with a
message naming the requested floor, which propagates directly up through
`requestHallCall` to the caller uncaught, becoming visible immediately
(rather than, say, being swallowed into a "best-effort, try again later"
queue) — the design intent is that "no capacity at all" is an operator-
visible, actionable failure, not a silently-retried background condition,
because a human (building management/maintenance crew) needs to know the
building has zero elevator capacity right now, not have the system quietly
paper over it.

## Production Architecture

### 28. If this moved from "in-process simulation" to "controls real elevator hardware," what's the first architectural boundary you'd introduce that doesn't exist today?

A hardware abstraction layer between `Elevator`'s logical state and the
physical car: today, `advanceFloor()` and `Door`'s transitions are
unconditionally trusted to reflect reality the instant they're called —
there's no notion of "I commanded the motor to move, but haven't yet
confirmed via sensor that it actually did." A real deployment needs a
clear seam where `Elevator`'s *intent* ("go to floor 7") is translated
into a hardware command, and a *separate, asynchronous* confirmation path
(sensor input) updates the logical floor position only once physically
verified — collapsing those two into one synchronous `tick()` call, as
done here for simulation simplicity, would be unsafe against hardware
lag, failure, or disagreement between commanded and actual position.

### 29. Would you run one process per building, or one process managing many buildings? Justify it against this codebase's actual concurrency and state design.

One process per building (or per independently-operated elevator bank,
per Q9 in STAFF_LEVEL_DEEP_DIVE.md). This design's concurrency model
(`synchronized` per-car `tick()`, a single shared simulation clock driving
`ElevatorController.tick()`) is built around a tight, low-latency,
single-building control loop — multiplexing many unrelated buildings'
independent physical hardware through one process buys nothing (no
meaningful resource sharing between buildings' control loops) while
introducing a shared blast radius: one process crash would take down
every building it manages, instead of just one. Isolating failure domains
by building is both simpler to reason about given this architecture and
safer operationally.

### 30. What's the minimal monitoring/observability you'd add before this could run unattended in production, given what's already instrumented (or not) in this codebase today?

Today, the only observability hook that exists is `Door.isJammed()` (a
getter, consumed by nothing — see STAFF_LEVEL_DEEP_DIVE.md Q19). Before
unattended production use, I'd add, at minimum: (1) an emitted metric/
alert the moment `isJammed()` becomes true (a physical safety-adjacent
condition a human should see within minutes, not discover from a
complaint); (2) an emitted metric for `NoElevatorAvailableException`
occurrences (the "entire building has zero capacity" case — should never
happen silently in production); (3) a periodic export of
`ElevatorSnapshot` per car (position, state, pending-stop counts) to
whatever monitoring/dashboard system the operator uses, since it's already
the exact right-shaped, already-immutable read model for that purpose with
no code changes needed to start exporting it.
