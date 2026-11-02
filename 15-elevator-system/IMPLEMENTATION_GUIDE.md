# Implementation Guide — Elevator System

A practical, code-ordered guide for building this from scratch in an
interview setting, separate from the strategic narrative in
`INTERVIEW_PLAYBOOK.md`. This is "what to type, in what order, and why
that order," plus the mistakes that are easy to make along the way.

---

## 1. Class-writing order

1. **`Direction`** enum (`UP`, `DOWN`, `IDLE`) with an `opposite()` method —
   zero dependencies, used by everything else.
2. **`DoorState`** enum (`CLOSED`, `OPENING`, `OPEN`, `CLOSING`,
   `OBSTRUCTED`) — zero dependencies.
3. **`Request`** interface, then **`HallRequest`**/**`CabinRequest`**
   records — depend only on `Direction`.
4. **`ObstructionSensor`** functional interface — trivial, needed before
   `Door`.
5. **`Door`** — depends on `DoorState`, `ObstructionSensor`.
6. **`ElevatorState`** interface — depends on nothing concrete yet (forward
   reference to `Elevator`, fine in Java since it's just a parameter type).
7. **`Elevator`** — depends on `Direction`, `Door`, `ElevatorState`
   (referenced as a field type, default state assigned via a static
   singleton once state classes exist — see note in step 8).
8. **The five `ElevatorState` implementations** (`IdleState`,
   `MovingUpState`, `MovingDownState`, `DoorOpenState`,
   `MaintenanceState`) — depend on `Elevator`'s public API, so write these
   *after* `Elevator`'s skeleton exists, even though `Elevator` itself
   references `ElevatorState` as a type. This is the one place you'll feel
   the circular-looking dependency; it resolves fine because `Elevator`
   only needs the *interface* to compile, not the concrete states.
9. **`SchedulingStrategy`** interface, then **`NearestCarScanStrategy`**
   and **`NaiveFcfsStrategy`** — depend on `Elevator`'s read-only accessors
   and `HallRequest`.
10. **Exception types** (`InvalidFloorException`,
    `NoElevatorAvailableException`, `ElevatorOutOfServiceException`) —
    can be written any time before step 11, zero dependencies.
11. **`ElevatorController`** — depends on everything above.
12. **`ElevatorSnapshot`** — depends on `Elevator`'s accessors only.
13. **Demo/`main()`** — depends on everything; write last.

## 2. Minimal compilable skeleton to start from

Get this compiling in the first 3–5 minutes, before adding any real logic —
it proves the shape of your design to yourself and the interviewer before
you invest in behavior:

```java
public enum Direction { UP, DOWN, IDLE }

public record HallRequest(int floor, Direction direction) {
    public HallRequest {
        if (direction == Direction.IDLE) {
            throw new IllegalArgumentException("Hall request must specify UP or DOWN");
        }
    }
}

public interface ElevatorState {
    ElevatorState step(Elevator elevator);
    Direction direction();
}

public class Elevator {
    private final int id;
    private volatile int currentFloor;
    private volatile ElevatorState state;

    public Elevator(int id, int startFloor) {
        this.id = id;
        this.currentFloor = startFloor;
        this.state = IdleState.INSTANCE; // written next
    }

    public synchronized void tick() {
        this.state = state.step(this);
    }

    public int currentFloor() { return currentFloor; }
}
```

Everything else is additive from here — stop queues, the Door, the
scheduler — layered on top of this already-compiling shape.

## 3. Order to implement internal logic

1. **`IdleState.step()`** first — the simplest state (just check "is there
   any pending stop; if so transition to Moving*"), gets the state-machine
   pattern itself working before tackling actual movement math.
2. **`Elevator.addStop()`** and the two `ConcurrentSkipListSet` queues —
   needed before `MovingUpState`/`MovingDownState` have anything to act on.
3. **`MovingUpState.step()`** — the real SCAN logic: advance one floor
   toward the nearest up-stop, or transition to `DoorOpenState` if arrived.
   Write `MovingDownState` as a mirror immediately after — resist the urge
   to "generalize" both into one parameterized class in an interview; two
   small, obviously-correct classes read better live than one clever
   unified one.
4. **`Door`'s open → hold → close cycle**, without obstruction handling
   yet — get the happy path working first.
5. **`DoorOpenState`** — delegates ticking to the `Door`, transitions back
   to `IdleState`/`MovingUpState`/`MovingDownState` once the door reports
   closed.
6. **Door obstruction handling** — layer this in once the happy path
   works; it's the single most important edge case to narrate explicitly
   (§6 below).
7. **`NearestCarScanStrategy`'s cost function** — start with just
   "distance to the floor," get a car being chosen at all, then add the
   direction-matching bonus/penalty, then (if time allows) the load and
   fairness terms.
8. **`ElevatorController`** wiring — this is mostly glue once the pieces
   above work; it should take the least time of any step if the order
   above was followed.

## 4. Important methods to get right first

- **`Elevator.tick()`** — the single most important method in the whole
  system; everything else exists to be called from inside, or to be acted
  upon by, this one method's state delegation.
- **`ElevatorState.step(Elevator)`** — get the *signature* right early
  (takes the elevator, returns the next state) — changing this signature
  later cascades through all five implementations.
- **`NearestCarScanStrategy.selectElevator`** — this is the method an
  interviewer will most want to see you reason through out loud,
  specifically the cost function's shape — narrate each term as you add
  it rather than writing the whole formula silently and explaining after.
- **`Door`'s obstruction check** — must be checked on *every* tick during
  `CLOSING`, not just once — a common and easy-to-miss mistake is checking
  the sensor only when `attemptClose()` is first called rather than on
  every subsequent tick while closing is in progress.

## 5. Common mistakes to avoid

- **Mistake**: Advancing the car's floor *and* changing its state in the
  same tick for the *first* movement tick (i.e., `Idle` → `MovingUp`
  immediately also moves one floor). **Why it's wrong**: it silently makes
  the first movement "free" relative to every subsequent one, which
  subtly throws off any wait-time measurement and is inconsistent with
  "one tick = one unit of state machine progress." **Fix**: the tick that
  transitions `Idle` → `MovingUp` only changes the state; the *next* tick
  is the first one where `MovingUpState.step()` actually calls
  `advanceFloor()`.
- **Mistake**: Using a plain `TreeSet` for the stop queues "to keep it
  simple," planning to swap to `ConcurrentSkipListSet` later "if
  concurrency comes up." **Why it's wrong**: the two classes are
  near-identical in API, so there's no actual time saved by starting with
  the wrong one, and if the interviewer then asks "what about concurrent
  requests," rewriting live under pressure is worse than starting right —
  mention this consciously if you don't have time to write it out: "I'd
  normally reach for `ConcurrentSkipListSet` here directly since I know
  concurrent dispatch is in scope."
- **Mistake**: Force-closing the door after a fixed number of obstruction
  retries, to "simplify" the state machine. **Why it's wrong**: this is a
  physical safety property, not a nice-to-have — a real interviewer will
  immediately flag this as wrong. **Fix**: retry indefinitely (optionally
  surfacing a `isJammed()` signal for monitoring after N retries), but
  never force-close.
- **Mistake**: Letting `NearestCarScanStrategy` silently return `null` or
  the first elevator in the list when no elevator is eligible (e.g., all
  in maintenance). **Why it's wrong**: a silent wrong-but-non-crashing
  result (dispatching to a maintenance car) is worse than a loud failure
  — it could send a passenger-visible hall call to a car that will never
  move. **Fix**: throw `NoElevatorAvailableException` explicitly.
- **Mistake**: Treating `pendingStopCount()` as free (O(1)) when reasoning
  about complexity out loud. **Why it's wrong**: `ConcurrentSkipListSet.
  size()` is actually O(n) (see FOLLOW_UP_QUESTIONS.md Q15) — stating this
  incorrectly in an interview is a small but avoidable accuracy miss.

## 6. Edge cases to handle, and how to narrate them live

- **Door obstruction during close**: say out loud, "I'm going to check the
  obstruction sensor on every tick while the door is closing, not just
  once — if it's obstructed, I reopen and restart the hold timer rather
  than forcing the close."
- **Hall call for the floor the car is already parked at, door closed**:
  say, "if the car is idle exactly at this floor, I'll open the door
  immediately rather than adding it to the stop set, since 'arrival'
  logic only fires during movement — this is a one-line special case
  worth calling out rather than silently handling."
- **All cars in maintenance**: say, "I want this to be a loud, typed
  exception, not a silently-queued request, since a human needs to act."
- **Duplicate hall call for an already-queued floor**: say, "this is
  automatically a no-op because I'm using a `Set`, not a `List` — worth
  mentioning so it's clear it's intentional, not accidental."
- **Starvation**: say, "I'm aware a pure nearest-car heuristic can let one
  car sweep one direction indefinitely if new calls keep appearing ahead
  of it — I'd add a time-based fairness penalty once a car's been
  sweeping past a threshold, to de-prioritize (not block) further
  same-direction assignments to it."

## 7. What to omit when time is tight

In priority order (same as `INTERVIEW_PLAYBOOK.md §13`, repeated here for
convenience since it's directly implementation-relevant):
1. `NaiveFcfsStrategy` — describe verbally instead of coding.
2. The fairness/starvation penalty term — mention verbally.
3. `MaintenanceState` — reduce to a one-line no-op state.
4. `ElevatorSnapshot`/reporting DTOs — use direct getters in a quick demo
   instead.
5. **Never omit**: the State-pattern movement logic, the door obstruction
   check, and a direct verbal statement about thread-safety.

## 8. A 20–30 minute simplified version, concretely

If given a hard 20–30 minute budget (e.g., a phone screen rather than a
full onsite round), build exactly this and nothing more:

1. `Direction` enum (1 min).
2. `HallRequest` record, floor + direction only, no validation (1 min).
3. `Elevator` with: `id`, `currentFloor`, a single `TreeSet<Integer> stops`
   (merge up/down into one set — explicitly state you're simplifying this
   away for time), and a `tick()` method using a plain `if/else` on
   direction rather than the full State pattern — say out loud: "Given the
   time, I'm going to inline the state transitions with a conditional
   rather than build out the full State pattern classes, but I want to be
   clear that's a time-based simplification, not what I'd ship — the next
   thing I'd refactor toward is one class per state." (10 min)
4. A single `ElevatorController` with one `List<Elevator>` and a
   `requestHallCall` that just does a linear scan picking the elevator
   with the smallest `Math.abs(floor - currentFloor)` — no load/fairness
   terms at all (8 min).
5. One or two lines verbally on: "a real version needs a Door class with
   obstruction handling, and thread-safe queues for concurrent requests —
   happy to go into either if useful" (2 min, no code).

This simplified version intentionally sacrifices the State pattern and the
SCAN-direction-aware queue structure — call both out explicitly as
conscious, named simplifications rather than silently shipping a lesser
design and hoping it isn't noticed.
