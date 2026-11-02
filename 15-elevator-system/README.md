# Elevator System — Multi-Elevator Building Controller

A staff-level Low-Level Design of a building elevator controller: multiple
cars, external hall calls (floor + direction) and internal cabin requests, a
SCAN/LOOK-style nearest-car scheduler, a State-pattern car lifecycle, and an
obstruction-safe door protocol. Java 17+, package `lld.elevatorsystem`.

```
15-elevator-system/
  README.md                     <- you are here
  INTERVIEW_PLAYBOOK.md
  STAFF_LEVEL_DEEP_DIVE.md
  FOLLOW_UP_QUESTIONS.md
  IMPLEMENTATION_GUIDE.md
  diagrams/                     <- Mermaid diagrams referenced below
  src/main/java/lld/elevatorsystem/...
  tests/lld/elevatorsystem/...  <- JUnit 5 tests mirroring the package layout
```

---

## 1. Problem Understanding

**Plain explanation.** Design the software that controls every elevator car
in a building. Passengers interact with it in two distinct ways:

- **Hall call** — standing on a landing (a floor that is *not* inside any
  car), they press an **UP** or **DOWN** button. They are expressing a
  *direction of travel*, not a destination; the system must decide *which
  car* answers and *when*.
- **Cabin request** — once inside a specific car, they press a floor button.
  No dispatch decision is needed; that specific car must stop there.

The controller's job is to keep every car moving efficiently, answer every
call in bounded time (no passenger waits forever), and do all of this safely
(doors must never close on an obstruction) while many calls can arrive from
different floors at effectively the same instant.

**Real-world use case.** This is the textbook "multi-elevator dispatch"
problem asked at Staff-level interviews precisely because it forces a
candidate to combine four separate hard sub-problems — a scheduling
algorithm, a state machine, concurrent request intake, and a safety-critical
hardware interaction (the door) — into one coherent design, not just pick a
single pattern and stop.

**What the interviewer is evaluating.**
- Can you separate "which car should answer this call" (a *scheduling*
  concern) from "how does one car move" (a *state machine* concern)? Mixing
  these into one God class is the single most common failure mode.
- Do you reach for the right data structure for "give me the next stop in
  this direction" (ordered set / `TreeSet`-family), and can you justify its
  complexity versus a naive list scan?
- Do you *actually* reason about concurrency (multiple hall buttons pressed
  "simultaneously") instead of hand-waving "we'd use a lock"?
- Do you notice and address starvation risk in a greedy nearest-car
  algorithm, not just implement it blindly?
- Do you treat the door's obstruction sensor as a first-class safety
  requirement, not an afterthought?

**In scope.**
- Multiple elevators serving a single bank/building with a shared floor
  range.
- Hall calls (floor + UP/DOWN) and cabin requests (destination floor).
- A SCAN/LOOK-based nearest-car scheduler, compared explicitly against a
  naive FCFS/round-robin baseline.
- An explicit State-pattern lifecycle: `IDLE`, `MOVING_UP`, `MOVING_DOWN`,
  `DOOR_OPEN`, `MAINTENANCE`.
- Thread-safe concurrent dispatch from multiple simultaneous hall calls.
- A basic starvation-prevention (fairness) mechanism.
- Door obstruction retry/safety protocol.

**Out of scope** (called out explicitly, not silently ignored — see
§17 Production Evolution for where each of these would go):
- Destination-dispatch panels (where passengers key in their destination
  floor *before* boarding, common in modern high-rises) — a materially
  different dispatch algorithm, not a straightforward extension.
- Weight/overload sensors and precise passenger-count tracking (we track
  occupancy as a soft counter only, not wired into physics).
- Multiple elevator banks / zoning (e.g., express banks for floors 30+).
- Persistence of historical ride data, UI/physical panel hardware
  integration, fire-service / emergency recall modes.
- Real wall-clock timing — the simulation runs on a discrete logical "tick"
  rather than `Thread.sleep`, which is what makes it deterministically
  testable (see §15).

---

## 2. Requirement Gathering

### Functional — essential
1. A passenger can raise a hall call: `(floor, direction)`.
2. A passenger inside a car can raise a cabin request: `(elevatorId, floor)`.
3. The system assigns each hall call to exactly one car using a scheduling
   strategy that is explicitly pluggable (Strategy pattern).
4. Each car moves floor-by-floor, stopping at any floor it has been asked to
   visit, in an order that does not require more reversals than necessary
   (SCAN/LOOK).
5. Each car's door opens, holds, and closes automatically, and never closes
   while an obstruction is detected.
6. A car can be taken out of service (`MAINTENANCE`) and returned to service,
   without losing already-queued requests.
7. An operator/test can inspect every elevator's current floor, direction,
   state, door state, and pending stops.

### Functional — optional / explicitly deferred
- Destination-dispatch (grouping passengers by destination before boarding).
- VIP/priority floors, express zoning, parking optimization (sending idle
  cars to likely-demand floors).
- Weight-based overload rejection.

### Non-functional
| Dimension | Requirement | How addressed |
|---|---|---|
| Performance | Dispatch decision must be cheap per hall call | O(E) over the elevator fleet (E = car count, typically single digits–low tens); per-car stop lookups are O(log n) via `NavigableSet` |
| Scalability | Adding a car must not change dispatch/queue code | Fleet is `List<Elevator>`; `SchedulingStrategy` iterates it polymorphically — no car-count-specific logic anywhere |
| Availability | One car's failure/maintenance must not block the building | Scheduler excludes cars in `MAINTENANCE`; `NoElevatorAvailableException` only when literally all are down |
| Consistency | A requested floor is never silently dropped | Floors live in thread-safe ordered sets removed only on physical arrival (see §10, §11) |
| Extensibility | New dispatch algorithms / new states must not require touching existing ones | Strategy pattern for scheduling, State pattern for car lifecycle (Open/Closed) |
| Reliability | Door must never close on an obstruction | Obstruction check gates every close attempt (§13) |
| Thread-safety | Many floors can call "simultaneously" | `ConcurrentSkipListSet` queues + single-writer `tick()` per elevator (§11) |

**Interview-necessary vs. production-extension split.** Everything in the
table above except "Reliability" needs a working *implementation* in a 45–60
minute interview; "Availability" and "Scalability" mostly need a correct
*design* (the code must not structurally prevent them) rather than a fully
fleshed-out failover story — that belongs in §17.

---

## 3. Clarifying Questions

Documented here as the assumptions this implementation makes, each with the
reasoning for why it matters and how a different answer would change the
design:

1. **"Is there one shared floor range for all cars, or can cars serve
   different sub-ranges (e.g., an express car skipping floors 2–10)?"**
   *Why it matters*: a shared range means a hall call is always a candidate
   for every car; a split range changes the scheduler to a filtering
   problem first, dispatch problem second.
   *Assumption made*: single shared bank; cars *can* have different
   `minFloor`/`maxFloor` (the code supports it per-`Elevator`), but the demo
   uses one shared range. The building's validated range is the union
   across all cars (`ElevatorController` computes `min`/`max` over the
   fleet).

2. **"Do hall-call buttons distinguish UP vs DOWN, or is it a single
   button?"**
   *Why it matters*: direction-aware hall calls are what make "is this car
   already heading the right way, past this floor or not" a meaningful cost
   term; a single button degrades the scheduler to pure nearest-car.
   *Assumption made*: direction-aware (`HallRequest.direction`), matching
   real elevator bank UX.

3. **"Should a car distinguish 'hall-up-call at floor 5' from
   'hall-down-call at floor 5' as two different stops, or merge them into
   one stop at that floor?"**
   *Why it matters*: real bank controllers sometimes serve only the
   matching-direction call and leave the other queued; merging simplifies
   the data model considerably.
   *Assumption made (explicit simplification)*: merged — once a car is
   asked to stop at floor 5, the door opens regardless of which direction's
   button was pressed. This is a conscious interview-time simplification,
   stated up front rather than silently baked in, because solving it
   properly requires per-direction hall queues at each floor (a feature of
   *destination dispatch* systems, out of scope — see §1).

4. **"Can elevator cars be added or removed from the fleet at runtime?"**
   *Why it matters*: dynamic fleet membership forces every dispatch read to
   go through a lock/CAS over the fleet list rather than treating it as an
   effectively-immutable list.
   *Assumption made*: fixed at construction (`ElevatorController` takes
   `List<Elevator>` once); `enterMaintenance()`/`exitMaintenance()` cover the
   "temporarily out of service" case without needing fleet mutation.

5. **"What does 'fair' mean — minimize average wait, minimize worst-case
   wait, or bound the maximum wait (no starvation) even at the cost of
   average throughput?"**
   *Why it matters*: a pure greedy nearest-car optimizer can starve a
   request behind a long sweep; fixing that costs some average-case
   throughput.
   *Assumption made*: minimize average wait as the primary objective, with
   an explicit bounded-unfairness guard (the `consecutiveMovementTicks`
   fairness penalty in `NearestCarScanStrategy`, §10) rather than a hard
   starvation-proof guarantee — documented honestly as a soft mitigation,
   not a proof (see STAFF_LEVEL_DEEP_DIVE.md Q7).

6. **"Is the system simulated in discrete time steps, or does it run against
   a real wall clock?"**
   *Why it matters*: wall-clock based tests are flaky (sleeps, timing
   races); discrete ticks are fully deterministic and make even
   concurrency tests reproducible.
   *Assumption made*: discrete logical ticks (`ElevatorController.tick()`),
   with real `Thread`s only used to *submit requests concurrently*, never
   to drive movement.

7. **"How many passengers can one car hold, and does that affect
   scheduling?"**
   *Why it matters*: a fully loaded car should be disfavored by the
   scheduler even if it is physically nearest.
   *Assumption made*: `Elevator.capacity` + `occupancy` exist and are
   exposed, but are intentionally *not* wired into a hard rejection — only
   `pendingStopCount` feeds the scheduler's load-penalty term. Full
   occupancy-aware dispatch is noted as a production extension (§17)
   because it requires board/alight events this LLD does not model in
   depth.

---

## 4. Domain Modelling

| Type | Kind | Responsibility |
|---|---|---|
| `Direction` | enum | `UP` / `DOWN` / `IDLE`; `opposite()` helper |
| `DoorState` | enum | `CLOSED` / `OPENING` / `OPEN` / `CLOSING` / `OBSTRUCTED` |
| `Request` | interface (value-object contract) | Unifies `HallRequest` and `CabinRequest` behind `floor()`/`createdAtTick()`/`requestedDirection()` |
| `HallRequest` | record (value object) | An external landing call: floor + direction + tick raised |
| `CabinRequest` | record (value object) | An internal cabin button press: floor + tick raised |
| `ObstructionSensor` | functional interface | Abstraction over the door's physical obstruction hardware |
| `Door` | entity (owned 1:1 by an `Elevator`) | Door lifecycle + obstruction-retry protocol |
| `ElevatorState` | interface (Strategy-shaped, used as State pattern) | One behavior per lifecycle phase |
| `IdleState` / `MovingUpState` / `MovingDownState` / `DoorOpenState` / `MaintenanceState` | stateless singletons implementing `ElevatorState` | Each encapsulates exactly one phase's transition rule |
| `Elevator` | entity / State-pattern context | Owns its position, its two direction-ordered stop queues, its `Door`, and its current `ElevatorState` |
| `SchedulingStrategy` | interface (Strategy pattern) | Decides which `Elevator` answers a `HallRequest` |
| `NearestCarScanStrategy` | domain service | Production dispatch algorithm (§10) |
| `NaiveFcfsStrategy` | domain service | Deliberately naive baseline, kept for comparison |
| `ElevatorController` | domain service / aggregate root | Owns the fixed fleet, the pluggable strategy, and the shared simulation clock |
| `ElevatorSnapshot` | record (read-only DTO) | Immutable reporting view of one car, decoupled from live mutable internals |

**Composition / association / ownership.**
- `ElevatorController` *owns* (composition) the fixed list of `Elevator`s and
  *has-a* (association, swappable) `SchedulingStrategy`. The controller's
  lifecycle bounds the elevators' lifecycle — there is no scenario where an
  `Elevator` outlives its controller.
- `Elevator` *owns* (composition, 1:1, cannot exist independently) exactly
  one `Door`. A `Door` is meaningless without its elevator.
- `Elevator` *has-a* (association) current `ElevatorState`, but the state
  objects are stateless singletons — many elevators reference the *same*
  `IdleState.INSTANCE` concurrently. This is intentional: the State pattern
  here stores no per-elevator data in the state object itself, which is what
  makes singleton reuse safe (see §11).
- `NearestCarScanStrategy`/`NaiveFcfsStrategy` *use* (dependency, not
  ownership) `Elevator` purely through read-only accessors; they never call
  a mutating method on it. This one-way dependency is what keeps "deciding"
  and "executing" cleanly separated.
- `HallRequest`/`CabinRequest` are immutable value objects with no identity
  beyond their field values (`record`s) and no lifecycle of their own — they
  are created, read, and discarded; the elevator stores only the *floor
  number*, not the request object, once dispatched (see §10 for why).

---

## 5. Domain Invariants

| Invariant | Where / how enforced |
|---|---|
| A car never moves outside `[minFloor, maxFloor]` | `Elevator.advanceFloor()` clamps with `Math.min`/`Math.max`; `addStop()` throws `IllegalArgumentException` for out-of-range floors |
| A hall call must specify `UP` or `DOWN`, never `IDLE` | Enforced twice: `HallRequest`'s compact constructor, and `ElevatorController.requestHallCall()` before even constructing the request |
| A door never reports `CLOSED` while the obstruction sensor fires on a close attempt | `Door.attemptClose()` — the *only* place `CLOSING` is ever entered — checks the sensor immediately before the transition; see the `DoorTest.obstructionDuringCloseAttemptReopensInsteadOfClosing` negative-control test |
| A car reverses direction only when its current-direction queue is empty (or physically at the end of its range) | `MovingUpState`/`MovingDownState`'s `step()` — the *only* place direction changes — checks `upStops()/downStops()` emptiness before transitioning |
| A queued stop is never silently lost | A floor is removed from `upStops`/`downStops` only inside `MovingUpState`/`MovingDownState` at the exact tick the car arrives there (`remove(floor)` returning `true`); nothing else ever mutates these sets except insertion |
| A car in `MAINTENANCE` never moves or opens its door | `MaintenanceState.step()` is a no-op returning `this`; it is the only state that ignores pending stops entirely |
| The scheduler never assigns a hall call to a car in `MAINTENANCE` | `NearestCarScanStrategy`/`NaiveFcfsStrategy` both filter out (or infinitely penalize) elevators where `isInMaintenance()` is true |
| Elevator fleet membership is immutable after construction | `ElevatorController` stores `List.copyOf(elevators)` — the field itself is never reassigned, and the source list's mutability afterward doesn't leak in |

---

## 6. Architecture and Class Diagram

Full Mermaid diagrams live in `diagrams/`; summarized here and kept in sync
with the actual Java:

- [`diagrams/component-diagram.md`](diagrams/component-diagram.md) — module/package dependency direction.
- [`diagrams/class-diagram.md`](diagrams/class-diagram.md) — full class diagram matching `src/`.
- [`diagrams/state-machine.md`](diagrams/state-machine.md) — the `ElevatorState` transition graph (SCAN/LOOK expressed as states) plus the nested door sub-state-machine.
- [`diagrams/sequence-hall-call.md`](diagrams/sequence-hall-call.md) — hall call dispatch end-to-end, including the concurrency note.
- [`diagrams/sequence-door-obstruction.md`](diagrams/sequence-door-obstruction.md) — the obstruction retry protocol tick-by-tick.

---

## 7. Design Patterns

| Pattern | Why needed | Where applied | Alternative considered | Why rejected |
|---|---|---|---|---|
| **State** | A car's behavior (what one `tick()` does) is fundamentally different per lifecycle phase; encoding that as one method with a giant `switch` on an enum would violate Open/Closed every time a new phase (e.g. a future `EMERGENCY_STOP`) is added | `ElevatorState` + 5 concrete states, `Elevator` as context | A single enum field + `switch` statement in `Elevator.tick()` | Works for 5 states, but every new state means editing one giant method instead of adding one new class; also makes each state's unit tests entangled with every other state's logic in the same method |
| **Strategy** | "How do we pick a car for this hall call" is exactly the kind of algorithm that must be swappable without touching call sites, and the problem explicitly asks to compare two algorithms | `SchedulingStrategy` + `NearestCarScanStrategy` / `NaiveFcfsStrategy`, injected into `ElevatorController` | Encode the choice as a boolean flag (`useNearestCar`) with an `if/else` in the controller | Doesn't scale past two algorithms, and conflates "which algorithm" with "how to call it" inside the controller — violates Single Responsibility |
| **Value Object (records)** | `HallRequest`/`CabinRequest`/`ElevatorSnapshot` have no identity, no mutation, and clear equality-by-value semantics | `record HallRequest(...)`, `record CabinRequest(...)`, `record ElevatorSnapshot(...)` | Mutable POJOs with getters/setters | Would need manual `equals`/`hashCode`, risks accidental external mutation of "immutable" data, and adds boilerplate with zero behavioral benefit |
| **Dependency Injection (constructor)** | `Door` needs an `ObstructionSensor`; `ElevatorController` needs a `SchedulingStrategy` — both must be swappable for tests (fake sensors, naive-vs-scan comparison) without subclassing | `Elevator(..., ObstructionSensor)`, `ElevatorController(..., SchedulingStrategy)` | A static factory or a Singleton `DefaultObstructionSensor` | Singletons here would make it impossible to simulate a flaky sensor per-test without global mutable state — exactly the anti-pattern the engineering standards rule out |

**Patterns deliberately *not* used, and why:**
- **Observer** — tempting for "notify UI when elevator state changes," but
  nothing in this LLD's scope has a UI/subscriber; adding it now would be
  forced abstraction with no consumer. Noted as a production extension
  (§17) instead.
- **Singleton for `ElevatorController`** — a building legitimately could have
  more than one bank/controller (e.g., separate banks for different wings);
  hard-coding a single global instance would make that impossible later for
  no present benefit.
- **Builder for `Elevator`** — its constructor has 5 parameters, all
  required, none optional/defaultable — a builder would add indirection
  without solving a real telescoping-constructor problem.

---

## 8. API and Method Design

### `ElevatorController` (primary entry point)
```java
public Elevator requestHallCall(int floor, Direction direction);
public void requestCabinCall(int elevatorId, int destinationFloor);
public void tick();
public void setStrategy(SchedulingStrategy strategy);
public Elevator getElevator(int elevatorId);
public List<Elevator> elevators();
public List<ElevatorSnapshot> snapshot();
public long currentTick();
```
- `requestHallCall` returns the chosen `Elevator` (not `void`) so a caller
  (or test) can immediately assert/display which car answered, without a
  separate query. Throws `InvalidFloorException` (floor out of building
  range, or `direction == IDLE`) and `NoElevatorAvailableException`
  (propagated from the strategy when every car is in maintenance).
- `requestCabinCall` returns `void` — a cabin request only ever affects the
  car the passenger is already in, so there's nothing new to report back.
  Throws `NoSuchElementException` (unknown `elevatorId`),
  `ElevatorOutOfServiceException` (target car in maintenance), or
  `InvalidFloorException` (destination outside *that specific car's* range).

### `Elevator`
```java
public void addStop(int floor);          // throws IllegalArgumentException if out of [minFloor, maxFloor]
public void tick();
public void enterMaintenance();
public void exitMaintenance();
public boolean isInMaintenance();
public Direction direction();
public String stateLabel();
public Door door();
public NavigableSet<Integer> upStops();   // live view — read-only usage expected, not a copy (see §11)
public NavigableSet<Integer> downStops();
```

### `SchedulingStrategy`
```java
Elevator selectElevator(HallRequest request, List<Elevator> elevators, long currentTick);
// throws NoElevatorAvailableException
```
Deliberately takes the *whole* elevator list rather than, say, a `Stream` or
an already-filtered candidate list — so each strategy implementation is free
to apply its own filtering/cost logic without the controller needing to know
what "ineligible" means for every possible strategy.

### Validation responsibilities (who checks what)
- **`ElevatorController`**: building-wide floor bounds, hall-call direction
  validity, elevator-id existence, maintenance-state gating for cabin
  requests. This is where *cross-cutting* validation belongs because only
  the controller can see the whole fleet.
- **`Elevator`**: its *own* floor-range bounds on `addStop` (defense in depth
  — even if a future caller bypasses the controller, an individual car
  still protects itself).
- **`HallRequest`**: its own structural invariant (direction cannot be
  `IDLE`) in the compact constructor — the one invariant that is true
  *regardless* of any building context, so it belongs on the value object
  itself, not duplicated at every call site.

---

## 9. Core Implementation

The full, compiling implementation lives in `src/main/java/lld/elevatorsystem/`.
Key files, annotated:

- [`Direction.java`](src/main/java/lld/elevatorsystem/Direction.java),
  [`DoorState.java`](src/main/java/lld/elevatorsystem/DoorState.java) — core enums.
- [`request/Request.java`](src/main/java/lld/elevatorsystem/request/Request.java),
  [`request/HallRequest.java`](src/main/java/lld/elevatorsystem/request/HallRequest.java),
  [`request/CabinRequest.java`](src/main/java/lld/elevatorsystem/request/CabinRequest.java) — value objects.
- [`door/ObstructionSensor.java`](src/main/java/lld/elevatorsystem/door/ObstructionSensor.java),
  [`door/Door.java`](src/main/java/lld/elevatorsystem/door/Door.java) — door safety protocol.
- [`state/ElevatorState.java`](src/main/java/lld/elevatorsystem/state/ElevatorState.java) and its five
  implementations ([`IdleState`](src/main/java/lld/elevatorsystem/state/IdleState.java),
  [`MovingUpState`](src/main/java/lld/elevatorsystem/state/MovingUpState.java),
  [`MovingDownState`](src/main/java/lld/elevatorsystem/state/MovingDownState.java),
  [`DoorOpenState`](src/main/java/lld/elevatorsystem/state/DoorOpenState.java),
  [`MaintenanceState`](src/main/java/lld/elevatorsystem/state/MaintenanceState.java)) — the State pattern.
- [`elevator/Elevator.java`](src/main/java/lld/elevatorsystem/elevator/Elevator.java) — the car entity / State-pattern context (see the extensive javadoc there for the `ConcurrentSkipListSet` and threading-model rationale, excerpted in §10/§11).
- [`scheduler/SchedulingStrategy.java`](src/main/java/lld/elevatorsystem/scheduler/SchedulingStrategy.java),
  [`scheduler/NearestCarScanStrategy.java`](src/main/java/lld/elevatorsystem/scheduler/NearestCarScanStrategy.java),
  [`scheduler/NaiveFcfsStrategy.java`](src/main/java/lld/elevatorsystem/scheduler/NaiveFcfsStrategy.java) — the Strategy pattern + its cost model.
- [`controller/ElevatorController.java`](src/main/java/lld/elevatorsystem/controller/ElevatorController.java),
  [`controller/ElevatorSnapshot.java`](src/main/java/lld/elevatorsystem/controller/ElevatorSnapshot.java) — the building-wide controller and its read-only reporting DTO.
- [`demo/ElevatorSystemDemo.java`](src/main/java/lld/elevatorsystem/demo/ElevatorSystemDemo.java) — runnable `main()` demonstrating concurrent dispatch, door obstruction, maintenance exclusion, and the naive-vs-SCAN comparison (numbers reproduced in §10).

The cost function at the heart of the scheduler (full file:
`scheduler/NearestCarScanStrategy.java`):

```java
private long cost(Elevator e, HallRequest request) {
    if (e.isInMaintenance()) {
        return Integer.MAX_VALUE;
    }
    int current = e.currentFloor();
    int target = request.floor();
    Direction carDirection = e.direction();
    long distance;

    if (carDirection == Direction.IDLE) {
        distance = Math.abs(current - target);
    } else if (carDirection == request.direction() && isAheadInSweep(current, target, carDirection)) {
        distance = Math.abs(current - target);
    } else {
        int sweepEnd = carDirection == Direction.UP ? e.maxFloor() : e.minFloor();
        distance = Math.abs(current - sweepEnd) + Math.abs(sweepEnd - target);
    }

    long loadPenalty = (long) e.pendingStopCount() * LOAD_PENALTY_PER_STOP;
    long fairnessPenalty =
            (carDirection == request.direction() && e.consecutiveMovementTicks() > FAIRNESS_TICK_THRESHOLD)
                    ? FAIRNESS_PENALTY : 0;
    return distance + loadPenalty + fairnessPenalty;
}
```

---

## 10. Data Structures and Algorithms

### The core choice: per-car direction-ordered request queues

Each `Elevator` owns:
```java
private final NavigableSet<Integer> upStops = new ConcurrentSkipListSet<>();
private final NavigableSet<Integer> downStops = new ConcurrentSkipListSet<>(Comparator.reverseOrder());
```

**Why a `NavigableSet` (`TreeSet`/`ConcurrentSkipListSet`-family) at all?**
The SCAN/LOOK algorithm's *entire* data-structure requirement reduces to one
operation performed once per tick: *"what is the next floor to visit in my
current direction of travel?"* Because the set is kept sorted by floor
number, this is just "the smallest element remaining" (we always advance one
floor and check `contains`/`remove` on the exact next floor, which is O(log
n), and emptiness/extremes are O(1)). No alternative needs to be consulted
or compared — the ordering *is* the scheduling decision for one car's own
queue.

**Why `ConcurrentSkipListSet` specifically, not plain `TreeSet`?**
`TreeSet` gives identical ordering/complexity guarantees but is not
thread-safe. In this system, a car's stop set is written concurrently from
two directions at once: (a) any number of hall-call dispatcher threads
calling `addStop()`, and (b) the car's own tick thread removing a floor the
instant it is visited. A `TreeSet` under this access pattern risks a
`ConcurrentModificationException` (fail-fast iterators) or silent
corruption of the red-black tree's internal pointers. `ConcurrentSkipListSet`
is a lock-free (CAS-based) skip list that implements the same
`NavigableSet` contract with the same **O(log n)** complexity for
`add`/`remove`/`ceiling`/`floor`, but its iterators are *weakly consistent*
— safe to read concurrently with writers, never throwing
`ConcurrentModificationException`.

### Complexity comparison

| Data structure | `addStop` | "next stop in direction" | Thread-safe? | Verdict |
|---|---|---|---|---|
| Unsorted `ArrayList`, scanned for min/max each tick | O(1) append | O(n) scan every tick | No (needs external lock + still not iterator-safe under concurrent writes) | Rejected: turns every tick into an O(n) scan; at n stops this is the dominant cost of the whole simulation loop |
| `PriorityQueue` (min-heap) | O(log n) | O(log n) peek, but **cannot efficiently re-sweep** — a heap gives you the global minimum, not "the next element past my current floor in a given direction", and removing an *arbitrary* already-visited floor mid-sweep is O(n) | No | Rejected: a heap optimizes for "always take the global min" which is the *wrong* query for LOOK (you want the next element *ahead of the car*, not the global min, once the car has reversed direction once already in its lifetime) |
| Plain `TreeSet` | O(log n) | O(log n) via `ceiling()`/`floor()` | No | Correct complexity, wrong concurrency story for this system |
| **`ConcurrentSkipListSet`** (chosen) | O(log n) expected | O(log n) expected via `ceiling()`/`floor()`/`first()` | **Yes** — lock-free, weakly-consistent iteration | **Chosen**: same complexity as `TreeSet`, safe under the system's actual concurrent-access pattern |
| Two Red-Black trees guarded by one `ReentrantLock` per car | O(log n) + lock overhead | O(log n) + lock overhead | Yes, but coarse-grained | Rejected: correct but strictly worse than `ConcurrentSkipListSet` under read-heavy / low-contention access (every `tick()` would acquire a lock even when no concurrent writer is active) |

### Scheduling algorithm comparison: SCAN/LOOK (nearest-car) vs. naive FCFS

`NaiveFcfsStrategy` is O(1) per call (round-robin index) but is *completely
blind* to car position — it will cheerfully send a hall call to a car on the
opposite end of the building if that car is simply "next in rotation."
`NearestCarScanStrategy` is O(E) per call (E = fleet size, iterated once) and
factors in: current position, current direction (does the car already pass
this floor?), load (pending stop count), and a fairness penalty (below).

**Measured comparison** (reproduced exactly by
`ElevatorSystemDemo.strategyComparisonDemo()`, deterministic — no sleeps, no
randomness, so these numbers reproduce bit-for-bit on every run): two cars,
building floors 0–20; car 1 idle at floor 0, car 2 moved to floor 20 before
the wave starts; then 10 `DOWN` hall calls raised in one wave at floors `{2,
18, 4, 16, 6, 14, 8, 12, 10, 3}` (scattered across the whole building, the
last one — floor 3 — deliberately close to car 1):

```
NAIVE FCFS         average wait = 20.2 ticks (floor 3, requested last, waited 43 ticks)
NEAREST-CAR SCAN   average wait = 19.3 ticks (floor 3, requested last, waited 10 ticks)
```

Two things worth being honest about, because a staff-level reviewer will
immediately ask "that average difference looks small — why?":
1. **The average wait is close in this particular scattered workload**
   because half of the ten requests are, by construction, genuinely
   ambiguous (near the midpoint) — for those, naive round-robin and
   nearest-car happen to agree fairly often. The *average* understates the
   benefit because it is dragged down by the easy cases both algorithms get
   right.
2. **The tail / worst-case is where the real win shows up**: the floor-3
   request, raised *last*, is routed by naive FCFS to whichever car is
   simply next in rotation (which happened to be the far car), waiting
   **43 ticks**, versus nearest-car correctly recognizing car 1 is right
   there and serving it in **10 ticks** — a 4.3x improvement on exactly the
   kind of call a passenger would complain about. See
   STAFF_LEVEL_DEEP_DIVE.md Q4 for the matching worst-case workload where
   nearest-car's *greediness* itself becomes the problem (clustering every
   call onto one car while a second sits idle) — presented honestly rather
   than cherry-picked away.

### Starvation prevention

Pure greedy nearest-car dispatch has a known failure mode: if new
same-direction hall calls keep appearing ahead of a car in its current
sweep, the car can be kept moving in that direction indefinitely, and its
*opposite*-direction queue (e.g., downward calls raised while it sweeps
up forever) never gets serviced. This implementation mitigates it with a
**fairness penalty** rather than a hard proof of boundedness (see
STAFF_LEVEL_DEEP_DIVE.md Q7 for the honest limits of this):

```java
private static final int FAIRNESS_TICK_THRESHOLD = 25;
private static final int FAIRNESS_PENALTY = 1000;
...
long fairnessPenalty =
        (carDirection == request.direction() && e.consecutiveMovementTicks() > FAIRNESS_TICK_THRESHOLD)
                ? FAIRNESS_PENALTY : 0;
```

`Elevator.consecutiveMovementTicks()` counts how many ticks in a row a car
has kept moving in the *same* direction without reversing (reset to 0 the
instant it reverses or goes idle). Once a car has been sweeping one
direction for more than `FAIRNESS_TICK_THRESHOLD` ticks, the scheduler adds
a large penalty to *new* same-direction assignments to that car — it is
still allowed to finish the stops it already committed to (so existing
passengers already boarding or already queued are never abandoned
mid-sweep), but it stops being handed *more* work in that direction,
letting its queue drain and letting it reverse to serve the starved
opposite-direction queue sooner. This is tested directly in
`NearestCarScanStrategyTest.fairnessGuardDeprioritizesACarThatHasBeenSweepingTheSameDirectionTooLong`.

### Other data structures used and why
- `AtomicLong tickCounter` (`ElevatorController`) — the shared simulation
  clock; `AtomicLong` gives lock-free, visible-to-all-threads increments
  without a dedicated lock object.
- `AtomicInteger occupancy` (`Elevator`) — a soft passenger counter updated
  via `getAndUpdate`/`updateAndGet`, avoiding read-modify-write races without
  a lock.
- `Map<Integer, Elevator> byId` (`ElevatorController`) — O(1) cabin-call
  routing by elevator id, built once at construction (`Collectors.toMap`)
  since the fleet is immutable after construction.

---

## 11. Concurrency and Thread Safety

This system has real shared mutable state even though it is "just an LLD":
multiple threads raise hall calls concurrently, and a separate simulation
clock advances every car's physical state. Every claim below is justified
against the actual code, not asserted.

### What is shared, and by whom
| Shared state | Writers | Readers | Protection |
|---|---|---|---|
| `Elevator.upStops` / `downStops` | Any number of dispatcher threads (`addStop`) **and** the car's own tick thread (`remove` on arrival) | The scheduler (`NearestCarScanStrategy` reading `pendingStopCount`), tests, snapshots | `ConcurrentSkipListSet` — lock-free, safe for concurrent read/write/iterate (§10) |
| `Elevator.currentFloor`, `Elevator.state` | Exactly one thread per elevator: whoever calls `tick()` (the controller's single simulation clock, by contract) | Dispatcher threads (via the scheduler's cost function), snapshot/reporting code | `volatile` fields for cross-thread *visibility* (so a dispatcher thread reading `currentFloor` concurrently with a `tick()` call always sees a recent value, never a torn/stale one) + `tick()`/`enterMaintenance()`/`exitMaintenance()` marked `synchronized` as a defensive single-writer guard |
| `Elevator.consecutiveMovementTicks` | Same single tick-thread as above | Scheduler's fairness-penalty cost term | `volatile` |
| `Elevator.occupancy` | Any thread calling `boardPassenger()`/`unboardPassenger()` | Scheduler's load term (not currently used there, but exposed), reporting | `AtomicInteger` with `getAndUpdate`/`updateAndGet` (atomic compare-and-set loop, no lock) |
| `Door.state`, hold/retry counters | Exactly one thread: whichever thread calls the owning elevator's `tick()` (door is only ever ticked from inside `DoorOpenState.step()`) | Snapshot/reporting code | No explicit synchronization — safe *only* because of the single-writer contract documented on `Elevator`; see "known limitation" below |
| `ElevatorController.tickCounter` | The single simulation-clock thread calling `tick()` | Any dispatcher thread (`requestHallCall` reads it to timestamp a `HallRequest`) | `AtomicLong` |
| `ElevatorController.strategy` | Whoever calls `setStrategy()` (typically a single test/admin thread, but not contractually restricted) | Every `requestHallCall` call | `volatile` field — safe publication of the new strategy object to subsequent reads; the small window where two concurrent `requestHallCall`s could observe *different* strategies around a swap is an accepted, documented race (an `ElevatorController` is not designed to have its dispatch algorithm hot-swapped during live traffic in the first place) |
| `ElevatorController.elevators` (the `List` reference itself) | Nobody — assigned once in the constructor via `List.copyOf` | Every method | Effectively immutable; no synchronization needed because the reference never changes and `List.copyOf` defensively snapshots the input |

### The one documented benign race

Two hall calls arriving at (almost) the same instant each independently (a)
read live elevator state to compute the best car, then (b) insert a stop
into that car's queue. There is no lock spanning both steps. Consequence:
both calls might compute "car 2 is best" based on stale-but-not-corrupt
state and both get routed to car 2, when ideally the second should have gone
to car 3. This is **never a correctness bug** — both floors are still
reliably added to a valid car's queue and both will be visited — only a
**throughput** suboptimality in the rare case where the race window is hit.
Eliminating it would require a single global lock serializing *every* hall
call building-wide, turning dispatch into a single-threaded bottleneck for a
provably rare, non-correctness-affecting event — a bad trade, documented
explicitly in `ElevatorController`'s class javadoc rather than silently
accepted.

### Why `tick()` is `synchronized` even though "only one thread calls it"
Defense in depth: the *documented* contract is "exactly one simulation-clock
thread drives movement," but `synchronized` costs nothing (zero contention
in the common case — uncontended `synchronized` in modern JVMs is a cheap
biased/thin lock) and converts a *silent data race* into, at worst, a
harmless serialization point if that contract is ever violated by a future
caller (e.g., a well-intentioned refactor to a per-elevator worker-thread
model). This is a concrete example of "every synchronization decision must
be justified, not asserted" — the justification here is explicitly "cheap
insurance against a documented-but-not-compiler-enforced invariant," not
"because concurrent code should have locks."

### Known limitation: `Door` has no synchronization of its own
`Door`'s fields are plain (non-volatile, non-atomic) because they are only
ever touched from inside `Elevator.tick()`, which is itself `synchronized`
and which the whole system promises is single-writer. If that promise is
ever broken (e.g., a hardware driver calling into `Door` directly from an
interrupt thread, bypassing the elevator), `Door` would have a genuine data
race. This is a scope decision, stated plainly rather than hidden: the
`ObstructionSensor` interface is explicitly the extension point for
hardware callbacks (its javadoc says the sensor implementation, e.g. an
`AtomicBoolean`-backed one, is responsible for *its own* thread-safety),
precisely so `Door` itself does not need to become thread-safe for a
scenario this LLD does not model.

### Deadlock analysis
There is no scenario in this codebase where two locks are acquired in
different orders by different threads: the only `synchronized` methods are
on `Elevator` itself (`tick`, `enterMaintenance`, `exitMaintenance`), each
elevator has its own monitor, and no method ever acquires a second
elevator's lock while holding the first's (the scheduler reads *multiple*
elevators' state, but exclusively through non-synchronized, volatile/atomic
accessors — never through a synchronized method). No nested lock
acquisition exists anywhere in the system, so classic lock-ordering deadlock
is structurally impossible, not just empirically unobserved.

### Concurrency test
`ElevatorControllerConcurrencyTest.manyFloorsCallingSimultaneouslyAreAllEventuallyServicedWithoutErrors`
(full file in `tests/`) launches 30 threads (pool sized to match the task
count — see the in-file comment explaining why a *smaller* pool here would
deadlock on the ready/go double-latch pattern), each raising a hall call for
a distinct floor at the same instant (gated by a `CountDownLatch`), then
asserts (a) no thread threw, and (b) after driving the simulation clock
forward, **every** car's pending-stop queues fully drain — which, given
stops are *only ever* removed upon physical arrival (§5 invariant), is
conclusive proof none of the 30 concurrently-raised requests were lost.

---

## 12. Persistence and Database Design

**This system is intentionally pure in-memory — no database is used, and
that is the correct choice, not an omission.** Justification:

- The entire domain — car positions, pending stops, door state — is
  *operational* state with a lifetime bounded by "the building is powered
  on." There is no requirement in scope (§1) to survive a process restart
  with state intact; a restarted controller legitimately re-initializes
  cars to a known position (e.g., via a real floor-position sensor in
  production, out of scope here).
- The access pattern is extremely latency-sensitive (a car's `tick()` runs
  potentially many times per second) and low-cardinality (single-digit to
  low-tens of elevators, dozens of floors) — exactly the profile where an
  in-memory data structure beats any database round-trip by orders of
  magnitude, and where "scale" is never going to be the bottleneck (§14).
- If this were extended toward a *real* building-management product (not
  this LLD's scope), the right persistent store would be for **analytics,
  not control**: a durable log of completed rides (floor, wait time,
  travel time, timestamp) in an append-only store (even a simple relational
  table: `ride_id`, `elevator_id`, `origin_floor`, `destination_floor`,
  `requested_at`, `served_at`, `completed_at`) for SLA reporting and
  predictive maintenance — explicitly a production-evolution concern (§17),
  not a core control-loop concern.

---

## 13. Failure Scenarios

| Scenario | Handling |
|---|---|
| Hall call for a floor outside the building's range | `InvalidFloorException` thrown before any elevator state is touched (validated in `ElevatorController.requestHallCall` before constructing the `HallRequest`) |
| Hall call with `Direction.IDLE` | Rejected explicitly, both at the controller boundary and (defense in depth) in `HallRequest`'s compact constructor |
| Cabin call naming a non-existent elevator id | `NoSuchElementException` from `ElevatorController.getElevator` — fails loudly rather than silently no-op'ing |
| Cabin call to a car under maintenance | `ElevatorOutOfServiceException` — the car is in service of nobody; silently queuing the stop would create a request that is never serviced until a human intervenes, which is worse than an immediate loud failure |
| Every elevator in the building is under maintenance | `NoElevatorAvailableException` propagated from the scheduling strategy; the controller does not attempt to "queue it for later" — in scope, this is a building-wide outage that needs a human/ops response, not a silent retry loop (see §17 for how a production system would alert here) |
| Door obstruction detected mid-close | Never reaches `CLOSED`; re-enters `OPEN` with a fresh hold window and retries indefinitely (never force-closes — passenger safety over throughput, §5 invariant) |
| Door obstruction repeats many times (possible real jam) | `Door.isJammed()` flips true after `JAM_ALERT_THRESHOLD` consecutive obstructions — a hook for a production alert (§17); the door still keeps retrying rather than giving up, because "give up" has no safe meaning for a physical door |
| Duplicate hall call for a floor/direction already queued | Idempotent by construction: `upStops`/`downStops` are `Set`s — adding the same floor twice is a no-op; no duplicate-stop bug is possible |
| A car is asked to stop at the floor it is already parked at | Handled explicitly in `Elevator.addStop`: `floor == currentFloor` triggers an immediate door open rather than being queued (queuing would never be visited by `MovingUp`/`MovingDownState`, since those only fire on *movement*, causing a silent stuck request) |
| Resource exhaustion (building with an extreme number of simultaneous hall calls) | Each hall call is O(E) to dispatch (E = fleet size) and O(log n) to enqueue; there is no unbounded buffering — the worst case is "every floor calls at once," which is still bounded by the number of floors, a fixed, small constant for any real building (§14) |
| Clock/tick thread crashes mid-simulation | Out of scope for recovery in this LLD (no persistence, §12) — a production deployment would run the tick loop under a supervisor that restarts it, re-deriving car state from real position sensors, not from in-memory Java state (§17) |

---

## 14. Performance and Scalability

**Single-process (this implementation's actual regime).**
- Dispatch: O(E) per hall call, E = fleet size (single digits to low tens in
  any real building — this will never be the bottleneck).
- Per-car tick: O(log n) for the stop-queue operation that fires on that
  tick (n = that car's own pending stop count, itself bounded by the
  building's floor count).
- Memory: O(E × n) total across the fleet; for any realistic building (say,
  8 cars × 50 floors) this is a trivial, fixed, small amount of heap —
  nowhere close to a concern.
- **Bottleneck that *would* matter at pathological scale**: if `E` (fleet
  size) were enormous, the scheduler's O(E) linear scan over all cars per
  hall call would dominate. Fix if ever needed: partition the building into
  independently-scheduled zones/banks (each bank has its own small `E`) —
  this is in fact how real very-tall buildings already work physically, not
  a hypothetical software fix.

**Multi-threaded (already present).** The concurrency model (§11) already
supports many dispatcher threads submitting hall calls in parallel without a
global lock; the only serialization point is each individual car's
`synchronized tick()`/stop-queue operations, which is inherently
fine-grained (contention only when two threads touch the *same* car at the
*same* instant).

**Distributed-extension (explicitly *not* recommended here).** This problem
does not benefit from distribution — a single building's elevator fleet is
a tightly-coupled, low-cardinality, latency-critical control system; sharding
it across machines would introduce network latency into a control loop that
currently runs in microseconds, for zero throughput benefit (the whole
*point* of §1's scope is that this is bounded by physical floor/car counts,
not by request volume). If a company *did* operate many buildings, the right
unit of distribution is **one controller process per building**, with a
separate (batch, not real-time) analytics pipeline aggregating ride logs
across buildings — not distributing a single building's own dispatch loop.

---

## 15. Testing Strategy

All tests are JUnit 5, under `tests/lld/elevatorsystem/`, mirroring the
`src/` package layout. Verified to actually compile and pass (see final
verification summary at the end of this README's accompanying report).

| Test class | Focus |
|---|---|
| `state/ElevatorStateMachineTest` | Unit: SCAN/LOOK movement rules in isolation — idle stays idle, single-stop travel, out-of-order insertion still visited in ascending order, reversal only after sweep exhaustion, immediate door-open at current floor, floor-bound clamping, maintenance preserves-but-ignores stops, exiting maintenance resumes |
| `door/DoorTest` | Unit + **negative control**: normal open/hold/close cycle; a sensor obstructed *exactly on the close attempt* (the faithful mutation of "no obstruction," not a crude always/never fake) proves the door does not reach `CLOSED`; eventual close once a flaky sensor clears; `isJammed()` surfaces after repeated obstructions without ever force-closing |
| `scheduler/NearestCarScanStrategyTest` | Unit: nearest-idle-car selection, preferring an already-sweeping car over a farther idle one, maintenance exclusion, `NoElevatorAvailableException` when the whole fleet is down, and the fairness/starvation-guard penalty actually changing the outcome |
| `scheduler/NaiveFcfsStrategyTest` | Unit + comparison: strict rotation order, demonstrates it *can* route to a far car over a near one (the point of the comparison), maintenance exclusion, all-maintenance exception |
| `request/HallRequestTest` | Boundary: `IDLE` direction rejected at construction; `CabinRequest` correctly reports `IDLE` as its own "requested direction" |
| `controller/ElevatorControllerTest` | Integration: hall call assignment + eventual arrival, cabin call isolation (only the named car moves), floor-bound/direction/unknown-id/maintenance validation failures, runtime strategy swapping, shared tick counter |
| `controller/ElevatorControllerConcurrencyTest` | **Concurrency + failure-injection-adjacent**: 30 threads racing to submit hall calls simultaneously; asserts zero exceptions and 100% eventual delivery (every queue fully drains) — see §11 for why "fully drains" is conclusive, not just suggestive, evidence |

**Representative test excerpt** (the obstruction negative control, full file
`tests/lld/elevatorsystem/door/DoorTest.java`):
```java
@Test
void obstructionDuringCloseAttemptReopensInsteadOfClosing() {
    // Faithful negative control: obstructed on the very tick the door would otherwise close.
    AtomicInteger checks = new AtomicInteger(0);
    ObstructionSensor obstructedOnce = () -> checks.incrementAndGet() == 1;
    Door door = new Door(obstructedOnce);
    door.beginOpening();
    door.tick(); // OPENING -> OPEN
    door.tick(); door.tick(); door.tick(); door.tick(); // hold exhausted -> attemptClose() -> obstructed

    assertEquals(DoorState.OBSTRUCTED, door.state());
    assertFalse(door.isClosed(), "a door must never report closed while the obstruction sensor fired on the close attempt");
}
```
This is deliberately *not* "sensor always returns true" (which would also
pass trivially and prove nothing about *timing*) — it fires exactly once, at
exactly the tick a naive implementation without the obstruction check would
have transitioned to `CLOSING`, which is the plausible bug a careless
refactor could actually introduce.

**What boundary/negative/failure-injection cases are covered, concretely:**
boundary floor values (`minFloor`/`maxFloor` clamping), negative input
(out-of-range floors, `IDLE` direction), invalid state transitions (cabin
call to a maintenance car), resource exhaustion analogue (all cars in
maintenance → no capacity), and the concurrency failure-injection style test
above (races deliberately maximized via a synchronized-release latch
pattern, not hoped-for via incidental thread scheduling).

---

## 16. Trade-offs and Alternatives

| Decision | Alternative | Pros/cons of alternative | Why this was chosen | When the alternative wins |
|---|---|---|---|---|
| `ConcurrentSkipListSet` per-car stop queues | A single shared `PriorityBlockingQueue` across the whole fleet, with a (car, floor) pair as the element | Simpler to reason about "one global queue"; but loses the SCAN ordering per car entirely (a priority queue orders by *one* key, not "ascending while going up, descending while going down" depending on car state) and couples every car's dispatch to one shared lock-free structure, which would need per-car filtering on every poll anyway | Per-car ordered sets give exactly the SCAN query shape for free and keep cars fully independent | If cars genuinely shared physical resources (e.g., a single shaft with two cars that must never pass each other — real in some buildings) a shared coordinated structure would become necessary |
| Discrete logical ticks, no real threads driving movement | Each `Elevator` runs on its own background thread calling `Thread.sleep` between floors | "More realistic" in that it mirrors real-time hardware | Fully deterministic tests (§15); zero flakiness; trivially fast test suite (no sleeps) | A true integration/demo environment wanting to visualize real-time movement for a UI would want wall-clock-paced ticks — easy to add as a thin wrapper (`while (true) { controller.tick(); Thread.sleep(500); }`) without touching any core logic |
| Hall calls merged into a single per-floor stop (no separate up/down hall queues) | Per-floor, per-direction hall-call queues, matched against the car's direction at arrival | More faithful to real elevator UX (a car moving up does not open for a down-only hall call) | Simpler data model, explicitly scoped as an interview-time simplification (§3, clarifying question 3) | A production destination-dispatch system genuinely needs this — noted as production evolution, not silently assumed away |
| Soft fairness penalty for starvation prevention | A hard "forced reversal" after N ticks regardless of pending work | Mathematically provable starvation bound | Avoids degrading already-committed sweeps for a theoretical worst case that the fairness penalty already substantially mitigates in practice; simpler to reason about and test | A safety-critical SLA ("no passenger waits more than X seconds, provably") would require the harder guarantee — see STAFF_LEVEL_DEEP_DIVE.md Q7 for the honest discussion of this gap |
| `NearestCarScanStrategy`'s small load penalty (1 per pending stop) | A much larger load-balancing weight, actively spreading clustered demand across multiple cars even at a per-request cost penalty | Better aggregate throughput under clustered demand (see the honest worst-case data in STAFF_LEVEL_DEEP_DIVE.md Q4) | Keeps the dispatcher's primary signal (actual physical distance) dominant and easy to reason about/tune incrementally | A building with predictable rush-hour clustering (e.g., lobby at 9am) would tune this weight up, or add a dedicated load-balancing term — exactly the kind of production tuning knob this design leaves room for without a rewrite |
| In-memory only, no persistence | Durable ride-log database from day one | Enables analytics/SLA reporting immediately | Out of scope (§1, §12); added complexity with zero interview-relevant benefit | A real production rollout (§17) would want this from day one for operational visibility |

---

## 17. Production Evolution

Kept explicitly separate from the core interview solution above:

- **Monitoring & metrics**: per-car uptime, average/percentile wait time per
  hall call, door-jam alert rate (`Door.isJammed()` is already the hook),
  dispatch-cost distribution (to catch the scheduler systematically
  favoring one car), maintenance-mode duration.
- **Logging**: structured per-event logs (hall call raised → assigned →
  arrived → door opened → door closed) with correlation ids, enabling the
  ride-log analytics table sketched in §12.
- **Distributed coordination**: not for a single building's dispatch loop
  (§14), but *is* relevant if one "controller" process must fail over to a
  standby without losing in-flight car assignments — a production system
  would replicate the (tiny) in-memory state to a hot standby or rebuild it
  from real floor-position sensors on failover, rather than trying to make
  the dispatch loop itself distributed.
- **Persistence**: durable ride-log table (§12) for SLA reporting,
  predictive maintenance (correlating `Door.isJammed()` events with
  eventual real maintenance tickets), and historical demand-pattern
  analysis to pre-position idle cars (a genuinely valuable production
  feature explicitly out of scope here — §1).
- **Fault tolerance**: real floor-position sensors as the source of truth
  (not in-memory `currentFloor`) so a process restart doesn't "forget"
  where cars physically are; a supervisor process restarting a crashed tick
  loop (§13); physical safety interlocks (door obstruction is modeled here,
  but a real system also needs e.g. overspeed brakes, which have nothing to
  do with this software layer).
- **Operational concerns**: an ops dashboard surfacing
  `ElevatorSnapshot`-shaped data per car in real time; an admin API wrapping
  `enterMaintenance()`/`exitMaintenance()` with audit logging (who took a
  car down, when, why); alerting when `NoElevatorAvailableException` would
  be thrown (i.e., *before* a passenger experiences it, by monitoring
  maintenance-mode count against fleet size).
- **API evolution / backward compatibility**: `SchedulingStrategy` and
  `ElevatorState` are both designed so new implementations can be added
  without touching existing ones (Open/Closed, §7); a destination-dispatch
  upgrade (§1, §3) would be introduced as a *new* `SchedulingStrategy` plus
  a *new* request type alongside `HallRequest`/`CabinRequest`, not a
  breaking rewrite of the existing ones.
