# Interview Playbook — Elevator System

A speakable, time-boxed playbook for driving this problem end-to-end in a
live interview. Pairs with `README.md` (the reference design) and
`IMPLEMENTATION_GUIDE.md` (the coding sequence).

---

## 1. How to open the discussion

Don't start coding or drawing boxes immediately. Restate the problem in your
own words, narrow the scope out loud, and name the sub-problems you can
already see:

> "So we're designing the controller for a bank of elevators in a building —
> multiple cars, passengers call from a floor wanting to go up or down, and
> passengers inside a car press a destination button. I see four things I
> want to nail down before I design anything: how we decide *which* car
> answers a hall call, how one car decides what to do next tick by tick, how
> we handle the door safely, and how we handle several floors calling at
> once. Let me ask a few questions first so I don't over- or under-build any
> of those."

This signals scoping discipline immediately — the single biggest
differentiator at Staff level.

## 2. First 5 clarifying questions to ask

1. "Is there one shared bank of elevators serving the same floor range, or
   could cars serve different sub-ranges (e.g., express cars)?"
2. "Do hall buttons distinguish UP vs DOWN, or is it one button per floor?"
3. "Should I worry about weight/capacity limits, or just logical floor
   requests for now?"
4. "Are we simulating this in discrete steps, or should I assume a real
   wall-clock/threaded model?"
5. "Is there a specific optimization goal — minimize average wait,
   minimize worst-case wait, or just 'don't be dumb about it' (i.e., beat a
   naive baseline)?"

(See `README.md §3` for the full reasoning behind each answer this
implementation assumed.)

## 3. Requirements to establish

Say these back explicitly before designing, in one breath:
- Hall calls: `(floor, direction)`, dispatched to exactly one car.
- Cabin requests: `(car, destination floor)`, always go to that specific car.
- A car moves efficiently — not literally "first come first served" on
  requests, but in a sweep that minimizes direction reversals.
- Each car's door must never close on an obstruction.
- Multiple hall calls can arrive "at the same time" and must all eventually
  be served, without crashing or losing one.
- A car can be pulled out of service and brought back.

## 4. How to identify core entities

Walk from "what are the nouns in the requirements" to "what's actually a
class":
- **Elevator** (a car) — clearly stateful, clearly an entity.
- **Door** — tempting to inline into Elevator, but it has its own lifecycle
  (open/hold/close/obstructed) complex enough to deserve its own class —
  call this out explicitly: "I could inline this into Elevator, but the
  obstruction-retry logic is enough of its own thing that I'll give it a
  class — that also makes it independently testable."
- **HallRequest / CabinRequest** — these are *data*, not entities: no
  identity, no behavior, immutable once created. Say "these are value
  objects — in Java I'd reach for records."
- **ElevatorController** (or "Building" / "ElevatorSystem") — the thing that
  owns the fleet and routes hall calls.
- **SchedulingStrategy** — name this as a first-class concept *before*
  writing any dispatch code, specifically because the problem explicitly
  wants a comparison between two algorithms: "I'm going to make the
  dispatch algorithm itself swappable from the start, since the problem
  wants me to compare two of them."

## 5. How to explain class relationships

Speak ownership, not just "has a":
> "The Controller owns a fixed list of Elevators — composition, they don't
> outlive it. Each Elevator owns exactly one Door — also composition, 1:1.
> The Controller *uses* a SchedulingStrategy, but doesn't own it in the same
> sense — it's injected and swappable at runtime, that's the Strategy
> pattern. HallRequest and CabinRequest aren't owned by anything long-term —
> they're created, read once by the scheduler, and then only the floor
> number itself is kept inside the elevator's queue."

## 6. How to draw the architecture

Draw (whiteboard or verbally, in this order):
1. Box: `ElevatorController` at the top.
2. Boxes below it: N `Elevator` instances (draw 2–3, say "...N more").
3. Off to the side: `SchedulingStrategy` interface, with an arrow *from* the
   controller *to* it (dependency, not ownership) — then two small boxes
   hanging off it: `NearestCarScanStrategy`, `NaiveFcfsStrategy`.
4. Inside one `Elevator` box: a small `Door` box, and a small "current
   state" box pointing at an `ElevatorState` interface with 5 bubbles
   (Idle/MovingUp/MovingDown/DoorOpen/Maintenance).
5. Two small queues coming off `Elevator`: "up stops" and "down stops",
   labeled "ordered by floor".

Narrate while drawing: "The reason I'm drawing two separate ordered queues
per car instead of one unsorted list is that the scheduling algorithm itself
— SCAN/LOOK — needs 'what's the next stop in my current direction,' and a
sorted structure gives me that for free."

## 7. Which interfaces to create first

In this order, and say why out loud:
1. `Request` (floor + direction + timestamp) — the smallest possible
   contract shared by hall and cabin requests.
2. `ElevatorState` — before writing `Elevator` itself, since `Elevator`'s
   `tick()` is going to be "delegate to current state," and you want the
   shape of that delegation decided first.
3. `SchedulingStrategy` — same reasoning; decide the dispatch contract
   before implementing any specific algorithm.

## 8. Which classes to implement first

1. `Direction` enum, `HallRequest`/`CabinRequest` records — trivial, gets
   you something compiling in the first 2 minutes.
2. `Elevator` with just `addStop`/`currentFloor`/plain field state — no
   State pattern yet, just enough to have something to test against.
3. The State pattern classes — `IdleState` first (trivial, sets up the
   pattern), then `MovingUpState`/`MovingDownState` (the real SCAN logic),
   then `DoorOpenState`.
4. `Door` with the obstruction protocol.
5. `SchedulingStrategy` + `NearestCarScanStrategy` (+ `NaiveFcfsStrategy` if
   time allows, for the comparison).
6. `ElevatorController` tying it together.

## 9. Which design patterns to introduce and when

- Introduce **State** the moment you're about to write `Elevator.tick()`'s
  body — say "I could switch on an enum here, but since this problem
  explicitly lists several states with real transition logic each, and asks
  for a state machine, I'll make each state its own class — State pattern."
- Introduce **Strategy** the moment you're about to write "how do we pick a
  car" — say "the problem wants a comparison between a smart algorithm and
  a naive one, so this has to be swappable — Strategy pattern, injected into
  the controller."
- Do **not** introduce Observer, Singleton, Builder, or Factory unless the
  interviewer specifically steers there — if asked "would you use a
  Factory for elevators," have a ready answer: "Not here — elevator
  construction is a single `new Elevator(id, minFloor, maxFloor, capacity,
  sensor)` call with no polymorphic construction logic; a factory would be
  indirection with no payoff."

## 10. How to discuss concurrency

Don't wait for the interviewer to ask "is this thread-safe" — raise it
yourself once the single-elevator logic is working:

> "Now, multiple floors can press their button at the same instant — that's
> genuine concurrency, not just hypothetical. Two things are shared: each
> car's stop queues, written both by dispatcher threads and by the car's own
> movement; and the car's position/state, which I want exactly one thread
> (the simulation clock) to ever mutate. For the queues, I'd reach for
> `ConcurrentSkipListSet` instead of `TreeSet` — same ordering and
> complexity, but safe for concurrent reads and writes without a lock. For
> position/state, I'll mark the fields `volatile` for visibility and make
> the `tick()` method itself `synchronized` as a cheap defensive
> single-writer guard."

If pushed "what can still go wrong": name the one benign race honestly —
"two hall calls at the exact same instant could both pick the same 'best'
car instead of spreading across two — that's a throughput hit, never a lost
request, and fixing it fully would mean one global lock over every hall
call in the building, which is a bad trade for a rare, non-correctness
issue."

## 11. How to discuss edge cases

Have these ready, in priority order (most interview-relevant first):
1. Door obstruction mid-close — "never force-close; retry with a fresh
   hold window; after N retries, flag it as jammed for monitoring, but keep
   retrying."
2. All cars in maintenance — "throw a clear exception rather than silently
   queueing forever; a human needs to know the building has zero capacity."
3. Hall call for a floor the car is already parked at — "open the door
   immediately rather than queueing — queueing would mean it's never
   actually visited, since 'visit' logic only fires on movement."
4. Starvation — "a pure greedy nearest-car scheduler can, in theory, keep a
   car sweeping one direction forever if new calls keep appearing ahead of
   it, starving the opposite queue; I add a fairness penalty once a car has
   been sweeping too long, so new work in that direction gets steered to
   another car."
5. Duplicate hall call for a floor already queued — "it's a no-op by
   construction, since the stop set is a `Set`."

## 12. How to handle interviewer interruptions

- **"Why not just use a list and sort it each time?"** — Answer with
  complexity directly: "That's O(n log n) every tick versus O(log n); for a
  small building it might not matter, but the ordered-set approach costs
  nothing extra to implement and scales better, so I'll just do it right
  from the start."
- **"What if I told you there are 50 elevators?"** — "The dispatch cost is
  O(E) per hall call, so 50 is still cheap — tens of microseconds. If it
  were 5000, I'd partition into zones/banks, which incidentally mirrors how
  real skyscrapers are built anyway."
- **"What about priority/VIP floors?"** — "I'd add that as a small
  additional term in the scheduler's cost function — the Strategy pattern
  means I don't have to touch the controller or the elevator state machine
  at all to add that."
- If an interruption derails your plan, explicitly re-anchor: "Good
  question — let me park that and come back to it once the core dispatch
  loop is working, so I don't lose the thread."

## 13. How to prioritise if time is running out

Cut in this order (most-cuttable first), and say so out loud rather than
silently skipping:
1. `NaiveFcfsStrategy` (the comparison baseline) — "I'll skip implementing
   the naive strategy and just describe why it's worse; the interesting
   code is the nearest-car one."
2. The fairness/starvation penalty — "I'll note the starvation risk
   verbally and describe the fix rather than coding the penalty term, to
   make sure the core movement logic is solid first."
3. `MaintenanceState` — "I'll mention it as a one-line no-op state and move
   on, since the SCAN movement logic is the higher-value code to show."
4. Full `ElevatorSnapshot`/reporting DTO — "I'll just expose getters
   directly for the demo rather than build a separate read model."
5. **Never cut**: the State-pattern movement logic itself, the door
   obstruction check, and at least one concurrency-safety sentence — these
   are the three things a Staff-level interviewer is most likely scoring.

## 14. Three full walkthroughs with realistic timelines

### 30-minute version
- 0–5 min: clarify scope (§2 above), state assumptions out loud.
- 5–10 min: entities + class relationships (verbal, maybe a quick box
  diagram) — Elevator, Door, Request types, Controller, Strategy interface.
- 10–22 min: code `Elevator` + State pattern (Idle/MovingUp/MovingDown/
  DoorOpen) directly — this is the core; skip `MaintenanceState` and the
  naive strategy.
- 22–27 min: code `NearestCarScanStrategy`'s cost function (just the
  distance/direction logic, skip load/fairness penalties — mention them
  verbally).
- 27–30 min: one sentence each on concurrency (ConcurrentSkipListSet + why)
  and door safety (never force-close) if not already covered.

### 45-minute version
- 0–7 min: clarify scope + requirements.
- 7–15 min: full domain model + class diagram (verbal/whiteboard), naming
  all patterns up front.
- 15–30 min: code `Elevator`, full State pattern (all 5 states), `Door`
  with obstruction retry.
- 30–40 min: code `SchedulingStrategy` + `NearestCarScanStrategy` including
  the fairness penalty; mention `NaiveFcfsStrategy` verbally with its cost
  model rather than coding it.
- 40–45 min: concurrency discussion (shared state table from your head,
  not written) + 2–3 edge cases from §11.

### 60-minute version
- 0–8 min: clarify scope + requirements + explicit assumptions.
- 8–18 min: full domain model, class diagram, and pattern rationale.
- 18–38 min: code `Elevator`, full State pattern, `Door`.
- 38–50 min: code both `SchedulingStrategy` implementations, including the
  fairness penalty; narrate the naive-vs-SCAN comparison and what metric
  you'd use to prove the difference.
- 50–57 min: concurrency deep dive (shared-state table, the one documented
  benign race, why `tick()` is `synchronized` even for "one writer").
- 57–60 min: production evolution teaser (monitoring, real sensors instead
  of in-memory position) — shows you know where the line between
  interview-scope and production is.

---

## 15. What I would say to the interviewer

- "I'm going to keep the scheduling algorithm and the car's own movement
  logic completely separate — one decides *which* car, the other decides
  *what that car does next* — because conflating those two is the most
  common way this problem turns into a tangled mess."
- "I'm using ordered sets per car instead of a single unsorted list because
  the SCAN algorithm's whole job is 'what's next in this direction,' and
  that's literally what a sorted structure gives you for free."
- "I'll be upfront: I'm merging hall-up and hall-down calls at the same
  floor into one stop for simplicity — a real system would track them
  separately so a car moving up doesn't open for a down-only call. I'm
  calling that out as a scoped simplification, not hiding it."
- "For concurrency, I want you to notice I'm not reaching for one big lock
  — each car's queue is independently thread-safe, and I'm being explicit
  about the one benign race I'm accepting instead of over-engineering it
  away."
- "A pure greedy nearest-car scheduler can starve a direction if demand
  keeps appearing in the other direction — I'm adding a small fairness
  penalty so a car that's been sweeping too long stops getting *more* work
  piled onto it, without breaking promises to requests it already
  committed to."
- "I deliberately didn't reach for a database — this is all operational,
  in-memory state with no requirement to survive a restart; if this became
  a real product, the thing I'd persist is a ride log for analytics, not
  the live dispatch state."
- "If you want me to go further, the next thing I'd build is destination
  dispatch — passengers key in their floor before boarding — but that's a
  materially different scheduling algorithm, not a small extension, so I
  scoped it out rather than half-build it."
