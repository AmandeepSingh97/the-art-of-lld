# Staff-Level Deep Dive — Elevator System

Twenty-two problem-specific questions a staff-level panel would actually push
on, each with a reasoned answer rather than a one-liner. These are not
generic "tell me about CAP theorem" questions — each one targets a specific
decision made in this codebase.

---

### Q1. You merged hall-up and hall-down calls at the same floor into a single stop. Walk me through exactly what breaks if two passengers at floor 5 — one going up, one going down — both press their button, and why your merge decision makes this worse, not just "simpler."

With the merge, both passengers' calls resolve to the same single floor
number, 5, added to whichever of `upStops`/`downStops` is relevant to
*whichever car gets dispatched first*. Concretely: if a car is currently
below floor 5 and moving up, the down-direction passenger's call also lands
in that car's `upStops` (because `Elevator.addStop` only looks at
`floor > currentFloor`, not at the passenger's intended direction). The car
stops, opens its door for *both* passengers, but it's heading up — the
down-direction passenger boards a car going the wrong way and must ride to
the top before reversing. This is a real UX regression versus a system that
tracks hall-up and hall-down as distinct queued items at a floor and only
opens the door for the matching direction (or defers the down call until a
downward-moving car arrives). The fix is architecturally small (a
`Map<Integer, EnumSet<Direction>> pendingHallDirections` consulted at
arrival to decide whether to *also* flag "and this direction too" before
opening) but was deliberately deferred because it couples the floor-level
hall state to *per-floor* button press tracking that doesn't exist yet in
this model — see Q11 for how that extension would actually be layered in.

### Q2. Your `NearestCarScanStrategy` reads `Elevator.currentFloor()`, `direction()`, and `pendingStopCount()` without holding any lock, then calls `addStop()` on the winner. Prove to me this can't result in a stop being silently lost, not just "usually works."

It cannot be lost because "lost" would require the floor to never end up in
either `upStops` or `downStops`, and `addStop()` is unconditional — given a
valid floor, it *always* inserts into exactly one of the two
`ConcurrentSkipListSet`s (or triggers an immediate door-open if the car is
already parked there), regardless of what state the car was in when the
cost was computed. The read-then-decide step can be based on **stale**
information (the car might have moved between the cost computation and the
`addStop()` call), which can make the *choice of car* suboptimal, but it
can never make the *insertion* fail or silently drop the floor — insertion
success is independent of the car's current position. The only way a stop
could be truly lost is a bug in `MovingUpState`/`MovingDownState` removing
the wrong floor, which is covered by
`ElevatorStateMachineTest.scanAlgorithmVisitsMultipleUpStopsInAscendingOrderWithoutReversing`
(verifies exact removal order) and
`ElevatorControllerConcurrencyTest` (verifies queues fully drain under
real concurrent insertion).

### Q3. Two hall calls at the same floor, same direction, arrive concurrently and both get dispatched to car A. A's `tick()` is simultaneously invoked by a buggy caller from a second thread, violating your single-writer contract. What actually happens, step by step?

Both hall calls are harmless even under this violation: `addStop` is
idempotent (same floor inserted twice into a `Set` is a no-op) and
thread-safe regardless of `tick()` activity. The dangerous part is the two
concurrent `tick()` calls. Because `Elevator.tick()` is `synchronized`, the
two calls are serialized on that elevator's intrinsic lock — they cannot
interleave arbitrarily. What you get instead is two full, sequential
`state.step(this)` invocations back-to-back, each advancing the car's
*logical* state machine one more "tick" than a correct single-writer caller
intended — e.g., the car might advance two floors in response to what the
caller thought was one simulation tick. This is a **logic bug** (the
simulation clock is now running at 2x speed for this one car relative to
the rest of the fleet) but **not a data race** (no torn reads, no
corrupted set state) — `synchronized` on `tick()` exists precisely to
convert "undefined behavior under a contract violation" into "well-defined,
merely-wrong-rate behavior." This is explicitly the "cheap insurance"
argument from README §11: it doesn't *prevent* misuse of the single-writer
contract, it *bounds the damage* from misuse to something debuggable
instead of something that corrupts shared collections.

### Q4. Show me a workload where your "smarter" `NearestCarScanStrategy` performs *worse* than the naive FCFS baseline, in aggregate, not just in a contrived edge case.

This isn't contrived — it's documented in README §10 and easy to reproduce:
two cars, one idle at floor 0, one idle at floor 20 (opposite ends of the
building); raise six hall calls all clustered near floor 20 (`{19, 17, 15,
18, 16, 14}`). `NearestCarScanStrategy` correctly recognizes car 2 is
closer for *every single one* of these calls and routes all six to it,
leaving car 1 completely idle. The result: car 2 serves all six requests
*sequentially*, and the average wait across the six is **worse** than naive
FCFS, which (blindly) spreads them across both cars, letting car 1 and car 2
work in parallel on three each, even though car 1's trips are individually
longer. This is the textbook tension between **per-request greedy
optimality** and **aggregate fleet utilization**: minimizing cost for each
request *independently* is not the same objective as minimizing total
system wait time, and a pure nearest-car heuristic optimizes the former. The
fix is exactly what real elevator dispatchers do: a stronger load-balancing
term in the cost function (this implementation's `LOAD_PENALTY_PER_STOP =
1` is intentionally small — see README §16 trade-off table — and would need
tuning upward, or replacing with a proper two-phase "which cars are
plausible, then balance among them" algorithm) for a production rollout
facing predictably clustered demand (e.g., lobby rush at 9am).

### Q4a. Given Q4, is your fairness/starvation penalty actually a load-balancer in disguise, or a different mechanism entirely? Don't conflate them.

Different mechanism, and conflating them is the trap. The fairness penalty
(`FAIRNESS_TICK_THRESHOLD`/`FAIRNESS_PENALTY`) only activates once a
*single* car has been sweeping *one direction continuously* for more than
25 ticks — it protects the *opposite-direction queue of that one car* from
starving, nothing about fleet-wide load distribution. In the Q4 scenario,
car 2 never triggers the fairness penalty at all (it's serving six
up-direction... actually down-direction calls in one sweep, which is
*exactly* efficient SCAN behavior, not starvation) — the fairness mechanism
has nothing to say about "car 1 sat idle the whole time." A real
load-balancing term would need to look across the *whole fleet's*
utilization, not one car's own sweep duration. This is a deliberate scope
boundary documented in README §16: starvation-prevention (one car, one
direction) and load-balancing (whole fleet, utilization) solve different
problems and this codebase only implements the first.

### Q5. Walk me through exactly what happens if the process crashes mid-tick — say, between `upStops.remove(floor)` succeeding and `door.beginOpening()` executing.

Within a single `tick()` call, `MovingUpState.step()` executes
`elevator.upStops().remove(floor)` and, if `true`, immediately calls
`elevator.door().beginOpening()` in the same synchronous call stack — there
is no `await`, no separate thread hop, no I/O between them. A JVM crash
*during* this would have to interrupt actual CPU execution mid-method,
which (absent a `SIGKILL` at exactly that instruction, or OOM at that
precise moment) is not something Java application code can meaningfully
guard against — there is no "transaction" here to roll back because
everything is in-memory and volatile by design (README §12: no persistence,
intentionally). The honest answer is: **this system has no crash-recovery
story at all**, because its entire purpose (§1 scope) is in-memory
simulation/control, not a durable workflow. If this were lifted into a real
building controller, the actual source of truth for "did the door open at
floor 5" would be a **physical sensor**, not this Java object's field —
recovery after a crash means re-reading physical elevator position/door
sensors on restart, not replaying in-memory state (README §17). The
important thing to say in an interview is not "it's fine," it's "this is
exactly why a real deployment wouldn't trust in-memory Java state as its
system of record for physical position — it would re-derive truth from
hardware on every restart."

### Q6. Your `ElevatorController` is a single Java object — what happens the moment this needs to span two JVM processes (e.g., a hot-standby for failover)?

Everything breaks, and it should be designed to, because distributing a
single building's control loop is the wrong move in the first place (README
§14). Concretely: `tickCounter` (an `AtomicLong`), the `ConcurrentSkipListSet`
stop queues, and the `volatile` car state are all JVM-local — none of them
are visible across a process boundary. A hot-standby failover design would
*not* try to replicate this object graph directly (e.g., via serialization
+ network sync on every tick, which reintroduces network latency into a
microsecond-scale control loop); instead it would make the standby rebuild
state from the same source of truth a cold restart would use — real
floor-position sensors and a re-scan of any physically-still-pending hall
button LEDs (hall call buttons in real elevator banks have their own
illuminated-state hardware, independent of controller software state, for
exactly this reason). The staff-level insight here is recognizing that
"make it distributed" is sometimes the *wrong answer* to a failover
question, and the right answer is "make the recovery path not depend on the
crashed process's memory at all."

### Q7. You admitted your starvation guard is a "soft mitigation, not a proof." Give me the actual adversarial sequence that defeats it.

Set `FAIRNESS_TICK_THRESHOLD = 25`. Consider a single car, building floors
0–1000 (contrived but illustrative), and an adversary who issues a new
same-direction (UP) hall call exactly every 24 ticks, each one just a few
floors ahead of the car's current position, forever. The car never
accumulates more than 24 consecutive ticks in one direction *as measured by
`consecutiveMovementTicks`* — because `consecutiveMovementTicks` only resets
on an actual direction reversal (not on a new request arriving), so this
counter-based guard is actually robust to that particular attack: a new
same-direction call arriving doesn't reset the counter, so by tick 26 the
fairness penalty *would* kick in. The real adversarial case is subtler: the
adversary issues calls that are *already within the car's current
`upStops` sweep window* before the threshold trips — since the fairness
penalty only affects *new dispatch decisions* (the scheduler choosing where
to send a *new* hall request), it cannot retroactively un-assign a stop
already committed to this car's queue. So: adversary floods car A's
`upStops` with 50 far-apart floors *before* 25 ticks elapse (e.g., all in
the first 5 ticks, while the fairness penalty is still inactive for this
car), then stops. Car A now must physically visit all 50 stops before it
can reverse to serve the starved `downStops` queue — the fairness penalty
never gets a chance to redirect *any* of those 50, because they were all
dispatched while the car still looked "fresh." The honest bound this system
actually provides is: *no unbounded stream of new assignments past the
threshold*, not *no unbounded pre-existing queue*. A provably bounded
system would need either a hard cap on queue depth per direction (rejecting
or redirecting new hall calls once a car's same-direction queue exceeds N
stops) or periodic forced reversal regardless of pending same-direction
work — both are real options, deliberately not implemented here because
they trade away guaranteed throughput for a worst case that, in any real
building, is vanishingly unlikely (50 simultaneous far-apart calls to one
car in 5 ticks is not a realistic passenger-arrival pattern) — but at
10,000 floors and adversarial/bot-driven demand it stops being hypothetical.

### Q8. Add a new requirement: "VIP passengers' hall calls should be served before anyone else's, even if it means reversing an in-progress sweep." Which existing classes do you touch, and which do you NOT touch — be specific.

**Touch**: `HallRequest` gains a `priority` or `isVip` field (a record field
addition — binary-incompatible but source-compatible with an overload, or
just accept the migration); `NearestCarScanStrategy.cost()` gains a
priority-aware term (e.g., subtract a large bonus for VIP requests, or — for
the "reverse an in-progress sweep" part specifically — this is the one place
the requirement genuinely exceeds the current architecture's reach, because
the Strategy only ever influences *new* assignment decisions, never
retroactively alters a car already mid-sweep (see Q7's distinction). To
actually honor "reverse an in-progress sweep," you'd need a new capability
on `Elevator` itself: something like `preemptForVip(int floor)` that can
force an immediate state transition even with a non-empty current-direction
queue — a genuinely new method, not just a new cost term.

**Do NOT touch**: `ElevatorController` (dispatch orchestration logic is
unchanged — it still just asks the strategy and calls `addStop`),
`MovingUpState`/`MovingDownState`/`IdleState`/`DoorOpenState` (their
transition rules for *non-preempted* travel are unchanged), `Door` (door
behavior is identical regardless of who's boarding), `CabinRequest`
(cabin requests aren't where VIP status would be expressed — it's a
hall-call concept), and `NaiveFcfsStrategy` (it's a baseline for
comparison; it's fine for it to simply not support VIP prioritization,
that's the point of keeping it naive).

This is a genuine test of the Strategy/State separation: most of a
meaningfully large feature slots into the *already-pluggable* seam
(`SchedulingStrategy`), and the one piece that doesn't (mid-sweep
preemption) cleanly identifies itself as needing a *new* capability on
`Elevator`, rather than forcing an awkward workaround inside the existing
states.

### Q9. This building now has 200 elevators (an unrealistic but illustrative stress case) and hall calls arrive at 10,000/second. Where does this system fall over first?

First bottleneck: `NearestCarScanStrategy.selectElevator` is O(E) per hall
call — at E=200 and 10,000 calls/sec, that's 2,000,000 elevator-cost
evaluations per second, each touching several volatile reads — almost
certainly still fast enough on modern hardware (sub-microsecond per
evaluation is plausible), but it's the first thing I'd profile, not guess
about. Second, more real bottleneck: physical plausibility — 200 elevators
and 10,000 calls/sec is not a real building, it's effectively an
synthetic/adversarial load test, meaning the *actual* production concern
this reveals is: this architecture assumes E (fleet size) stays in the
"single building" regime (single digits to low tens) and intentionally does
not horizontally shard dispatch (README §14) — at genuinely large E, the
correct fix isn't to optimize this O(E) scan further, it's to challenge
the premise that one `ElevatorController` should own 200 cars at all; real
200+ car installations are always split into physically separate banks
serving different floor ranges, each independently a small-E instance of
exactly this design. The staff-level answer is recognizing when "optimize
the algorithm" is the wrong response and "the premise itself doesn't match
reality" is the right one.

### Q10. If you had to ship a materially simpler version of this in two weeks instead of building the full thing, what's the first thing you'd cut, and what's the risk of cutting it?

Cut first: the fairness/starvation penalty and `NaiveFcfsStrategy`
entirely, shipping only `NearestCarScanStrategy`'s core distance+direction
cost term (no load penalty, no fairness penalty). Risk: under clustered or
adversarial demand (Q4, Q7), some passengers could wait meaningfully longer
than they would with the full design — but for a two-week MVP in a real,
small building (not an adversarial load test), the base nearest-car
heuristic alone already dramatically outperforms "no scheduling" and is a
reasonable, honestly-communicated v1. What I would **not** cut, even under
schedule pressure: the door obstruction-safety check (§5 invariant) —
that's a physical safety property, not a nice-to-have, and shipping without
it is a liability question, not an engineering-tradeoff question. Second
thing I would not cut: the `ConcurrentSkipListSet` choice for the stop
queues — reverting to an unsynchronized `TreeSet` "to save time" would
reintroduce `ConcurrentModificationException` risk under the system's
actual (not hypothetical) concurrent access pattern, which is a correctness
regression disguised as a shortcut, not a real time savings (the two types
are API-compatible — there's no implementation time saved by using the
wrong one).

### Q11. The `Request` interface has a `requestedDirection()` method that `CabinRequest` implements by always returning `IDLE` — is that a leaky abstraction, or a legitimate unification?

It's legitimate, but it's worth being honest about *why* it isn't leaky:
the method's contract (as documented) is "the direction a hall request
wants to travel in; cabin requests report IDLE because they have no
inherent direction preference of their own." This is a case where a shared
interface method has a *well-defined, documented, non-arbitrary* default
for one implementer — it's not "cabin requests don't really support this
concept, we just had to return something to satisfy the interface,"
it's "cabin requests genuinely have no directional preference, and IDLE is
the semantically correct value for that, the same way `Optional.empty()` is
the correct value for 'genuinely nothing here,' not a workaround." The test
of whether this is leaky: does any caller *need* to special-case
`CabinRequest` to avoid misusing this method? Searching the codebase: no —
`NearestCarScanStrategy.selectElevator` only ever receives `HallRequest`
(not the general `Request` interface) as its parameter type, specifically
*because* cabin requests never go through the scheduler at all (§8 API
design) — they're routed directly to their named car. So
`requestedDirection()` on `Request` is really only ever consumed in a
context where it's always a `HallRequest` in practice today — it exists on
the shared interface for forward-compatibility (a future unified request
pipeline) rather than because today's code needs polymorphism over it. That
is worth flagging as "this interface method is currently unused in its
polymorphic form" — a legitimate but honest callout of speculative
generality (see Q12).

### Q12. Following on from Q11 — is `Request` actually earning its keep as an interface today, or is it speculative generality you should cut?

Fair challenge. Today, `NearestCarScanStrategy` and `NaiveFcfsStrategy` both
take `HallRequest` directly, never `Request`; `ElevatorController` likewise
constructs and passes `HallRequest` concretely. The *only* place `Request`
as an interface is used polymorphically is... nowhere in production code —
it exists purely to document "these two types are conceptually siblings"
and give `CabinRequest` a consistent shape. Honest assessment: this is
borderline, but I'd keep it, for one reason — it's genuinely cheap (one
small interface, two one-line implementations, no runtime cost) and it
*does* communicate intent clearly in the domain model (§4) even without a
current polymorphic call site, which is different from, say, introducing a
whole Observer-pattern infrastructure nobody calls (README §7's explicit
"patterns not used" list) — those would carry real design/maintenance
weight for zero payoff. A one-method marker-ish interface with two obvious
implementers is defensible scaffolding for the next feature (unified
request history/logging, mentioned in §17) rather than true speculative
generality. If a reviewer insisted on cutting it, the fallback is fine too:
inline `floor()`/`createdAtTick()` separately on each record with no shared
interface — nothing today actually depends on the polymorphism.

### Q13. Your `ElevatorSnapshot` is a read-only copy taken at a point in time. What's the actual staleness window, and does any caller rely on it being fresher than it is?

The staleness window is bounded by however long elapses between
`ElevatorSnapshot.of(elevator)` being called and the caller reading its
fields — since it's an immutable `record` populated eagerly from live
accessors at construction time, it can never become *more* stale after
creation (there's no lazy evaluation), but it is stale *relative to the
live elevator* the instant any subsequent `tick()` or `addStop()` happens
on that same elevator. The demo (`ElevatorSystemDemo.printFleet`) and the
tests that use `controller.snapshot()` only ever use it for
point-in-time reporting/assertions immediately after a known quiescent
point (e.g., right after a bounded tick loop), never for a decision that
assumes it reflects "right now" at an indeterminate later time — so no
current caller actually relies on tighter freshness than it provides. The
risk case would be a *future* caller building, say, a live dashboard
polling `snapshot()` on an interval and displaying it as if up-to-the-tick
— that's fine for human-consumption dashboards (seconds-scale staleness is
irrelevant to a person looking at a screen) but would be the wrong
primitive for, say, a safety interlock that needs to know a door's state
synchronously before taking an action — that interlock should read
`elevator.door().state()` directly, not a cached snapshot.

### Q14. Your scheduler cost function mixes three different units — floor-distance, pending-stop count, and a tick-based fairness threshold — into one additive `long`. Defend that, or admit it's a code smell.

It's a legitimate, if slightly informal, weighted-sum heuristic — common in
real dispatch systems — but I'll be precise about what it actually claims
and doesn't: adding "distance in floors" + "pending stops × 1" +
"fairness penalty (0 or 1000)" implicitly asserts an exchange rate between
these (one pending stop is "worth" one floor of extra distance; exceeding
the fairness threshold is "worth" 1000 floors, i.e., effectively "never
pick this car for same-direction work unless literally nothing else is
available"). These weights (`LOAD_PENALTY_PER_STOP = 1`,
`FAIRNESS_PENALTY = 1000`) are not derived from any model of actual
passenger-perceived cost — they're hand-picked constants that happen to
produce reasonable behavior in the test scenarios exercised here
(README §16 documents this explicitly as a tunable knob, not a principled
weighting). The honest answer to "is this a code smell": it's a
**reasonable first approximation that should be called out as a tuning
surface**, not hidden as if the numbers were load-bearing science — a
production rollout would want these weights validated against real wait-time
data (A/B testing different weight values against measured passenger wait
percentiles), not just asserted. I'd defend keeping the *additive-cost
heuristic shape* itself (it's simple, fast, and easy to reason about/tune
incrementally) while being explicit that the specific constants are
starting points, not conclusions.

### Q15. What's your actual consistency guarantee for "every hall call will eventually be served"? Is it a proof, or an empirical observation from your tests?

It is a proof, conditional on a liveness assumption that is reasonable to
state explicitly: *given* that `ElevatorController.tick()` continues to be
called indefinitely (i.e., the simulation clock doesn't permanently stop)
and *given* that at least one elevator is not in `MAINTENANCE`, every
inserted floor is guaranteed to eventually be visited, because: (1) a
floor, once inserted into `upStops`/`downStops`, is only ever removed by
`MovingUpState`/`MovingDownState` upon physical arrival (§5 invariant,
enforced by code inspection — no other code path calls
`.remove()` on these sets); (2) a car with a non-empty `upStops` or
`downStops` can only be in `IDLE`, `MOVING_UP`, `MOVING_DOWN`, or
`DOOR_OPEN` (never stuck in a state that ignores pending stops, *unless*
it's `MAINTENANCE` — which is an explicit, operator-driven exception, not
an emergent bug); (3) each of those four states' `step()` method
unconditionally makes "progress" toward servicing pending stops every tick
(either moving one floor closer, or cycling the door) — there is no state
among them that can loop forever without eventually transitioning toward
service. Therefore the bound is: time-to-service is finite and bounded by
(distance to the stop) + (at most one full reversal sweep across the
car's configured floor range) + (door cycle time) — a concrete, calculable
upper bound, not just "my tests didn't find a counterexample." The
caveat that *does* weaken this to "soft guarantee, not hard SLA" is
exactly Q7's finding: that bound can be *large* (one full building sweep)
under adversarial same-direction flooding, which is why I'd never phrase
this as "no one waits more than X seconds" without the fairness-penalty
caveat attached.

### Q16. You chose records for `HallRequest`/`CabinRequest`. What API evolution problem does that create, and how would you actually solve it without a breaking change?

Records generate their `equals`/`hashCode`/`toString`/accessors from the
exact field list, and adding a field (e.g., VIP priority, per Q8) changes
the canonical constructor's signature — any caller using positional
construction (`new HallRequest(floor, direction, tick)`) breaks at compile
time the moment a field is inserted or reordered. The actual, non-breaking
evolution path: **add a new canonical constructor overload is not possible
for records** (a record has exactly one canonical constructor matching its
component list) — so the real options are (a) add the new field to the
*end* of the component list with a sensible default surfaced via a
secondary **static factory method** (`HallRequest.of(floor, direction,
tick)` that fills in `priority = NORMAL`), keeping the compact/canonical
constructor as the "full" one and steering callers toward the factory for
source compatibility, or (b) stop using a record for this type the moment
you need genuine optional/versioned fields, and move to a regular class
with a builder — which is in fact the textbook signal for "this is no
longer a good fit for `record`." I'd choose (a) for one additional field
(the current situation), and flag that a *second* evolution request would
be the trigger to reconsider whether `HallRequest` should still be a record
at all.

### Q17. Your `ElevatorController` constructor takes `List<Elevator>` and immediately defensive-copies with `List.copyOf`. What specific bug does that prevent, concretely — not "it's good practice," show me the actual scenario.

Concrete scenario: a caller builds `List<Elevator> cars = new
ArrayList<>(List.of(e1, e2))`, passes it to `new ElevatorController(cars,
strategy)`, and *keeps a reference to `cars`* for its own unrelated purposes
elsewhere (e.g., logging all configured elevators at startup). If the
constructor had stored `this.elevators = elevators` directly (aliasing the
caller's list), then later code doing `cars.add(new Elevator(3, ...))` —
intending only to log the new car, with no awareness that `cars` is also
the controller's live fleet — would silently add a *third elevator to the
controller's fleet* without going through any controller API, bypassing
`buildingMinFloor`/`buildingMaxFloor` recomputation (computed once in the
constructor from the *original* two cars) and the `byId` map (also built
once, now missing the third car's id). The result: a car that physically
exists in the fleet list, is iterated by `tick()`, but can *never* be
targeted by `requestCabinCall` (its id isn't in `byId`) and whose floor
range isn't reflected in hall-call validation. `List.copyOf` prevents this
entirely by making the controller's internal list provably independent of
whatever the caller does with their own reference afterward — a real,
specific aliasing bug, not a generic "defensive copying is good" platitude.

### Q18. If I told you this had to support "destination dispatch" (passenger keys in their target floor at a hall kiosk, before boarding) next quarter, which current abstraction would you be most worried about, and why?

Most worried about `SchedulingStrategy.selectElevator`'s signature itself:
`Elevator selectElevator(HallRequest request, ...)` fundamentally assumes a
hall call carries only `(floor, direction)` — destination dispatch's entire
value proposition is that the *destination* is known at call time, which
changes the dispatch decision from "which car is nearest in this direction"
to "which car's *existing passenger destinations* can this new passenger be
grouped with, to minimize total stops" — a genuinely different algorithm
class (closer to a bin-packing/clustering problem than a nearest-neighbor
cost function). This isn't a small signature change; it changes what
information flows from "the hall kiosk" all the way through to "which floor
gets added to which car's queue," because in true destination dispatch, the
system may not even let the passenger approach just any car — it directs
them to a *specific* car via a display at the kiosk, before boarding,
which means `HallRequest` would need to carry destination, and
`ElevatorController` would need a new entry point (`requestDestinationCall
(originFloor, destinationFloor)` returning *which specific car to walk to*,
not just "an elevator will come"). The current `Elevator`/`Door`/state
machine layer is mostly fine as-is (a car still just visits a sorted list of
stops); the risk is entirely concentrated in `SchedulingStrategy` and the
controller's public API shape — which, encouragingly, is exactly where the
Strategy pattern seam already is (README §7) — but I'd be honest that this
is a "new strategy implementation plus a new, parallel request-entry API,"
not a drop-in `SchedulingStrategy` swap, because the *information available
at dispatch time* itself changes, not just the formula.

### Q19. Your door's `JAM_ALERT_THRESHOLD` surfaces `isJammed()` after 3 consecutive obstructions — but nothing in this codebase actually *does* anything with that signal except expose a getter. Is that acceptable, or is it a half-finished feature?

It's an intentionally half-implemented *hook*, and the distinction matters:
a half-finished *feature* would be something that's supposed to work
end-to-end today but doesn't (e.g., an alerting pipeline that's wired up but
silently swallows exceptions); a *hook* is a deliberately exposed extension
point whose consumer is explicitly out of scope (README §17: "a production
system would raise a monitoring alert here"). The test for whether this is
acceptable: does the core safety property hold *without* anything consuming
`isJammed()`? Yes — the door never force-closes regardless of whether
anything observes the jammed flag; `isJammed()` is purely additive
observability, not something the safety logic depends on being consumed.
That said, I'd flag one real risk if this went further un-reviewed: if a
future engineer assumes `isJammed()` being true somewhere triggers
*automatic* intervention (e.g., assumes the system already pages someone),
that's a dangerous false assumption — worth a prominent comment (which the
javadoc already has: "a production system would raise a monitoring alert
*at this point*" — phrased as a recommendation to the reader, not a claim
about current behavior).

### Q20. You claim `ConcurrentSkipListSet` iteration is "weakly consistent" and therefore safe. What does that actually guarantee, and what does it specifically NOT guarantee that a naive reader might assume it does?

It guarantees: an iterator obtained from a `ConcurrentSkipListSet` will
never throw `ConcurrentModificationException`, will reflect the state of
the set *at some point* at or after the iterator's creation, and will
traverse elements in the set's defined order without skipping any element
that was present for the iterator's entire lifetime and never removed. It
specifically does **not** guarantee: that concurrent additions or removals
made *during* an iteration will or won't be reflected in that same
iteration (an iterator may or may not see a concurrently-added element — it
provides no snapshot isolation), and it does **not** guarantee that two
separate reads of the set (e.g., `size()` followed immediately by
`isEmpty()`, or `pendingStopCount()` summing two sets' sizes) are mutually
consistent with each other as of a single instant — each read observes an
independent snapshot-in-time of that one collection. Where this actually
matters in this codebase: `Elevator.pendingStopCount()` computes
`upStops.size() + downStops.size()` as two separate reads; under
concurrent modification, this sum could theoretically be momentarily
inconsistent with "the true combined count at any single instant" (e.g., if
a stop moves from one set's conceptual state to committed in the other
mid-read — though in practice stops aren't *moved* between the two sets,
only inserted/removed independently, so this specific risk doesn't actually
manifest here; I'm flagging the general guarantee gap, not a live bug).
This is a good illustration of "know precisely what your concurrent
collection promises," rather than treating "it's concurrent-safe" as a
binary, unqualified property.

### Q21. Final one: if a staff engineer joined this project tomorrow and had to make one architectural change to meaningfully improve this system's real-world fidelity, what would you tell them to do first, and why that one thing over all the others discussed here?

Replace the merged hall-call model (Q1/Q3 from README) with per-floor,
per-direction hall-call tracking. Reasoning for prioritizing this over
everything else in this document: it's the one simplification that
actively produces *wrong passenger-facing behavior* today (a car can open
its door for a passenger whose requested direction it isn't serving),
whereas every other gap discussed here (Q4's load-balancing weakness, Q7's
soft starvation bound, Q9's scale ceiling) degrades *efficiency* or
*worst-case latency*, not *correctness of the passenger experience* itself.
It's also the change with the clearest, most contained blast radius: it
touches `Elevator.addStop` (needs a direction parameter alongside the
floor), the arrival-detection logic in `MovingUpState`/`MovingDownState`
(needs to check "does the matching-direction hall call exist at this floor"
before opening, or open anyway but only clear the matching-direction flag),
and `HallRequest` (already carries direction — no change needed there) —
notably, it does **not** require touching `SchedulingStrategy`,
`ElevatorController`, or `Door` at all, because dispatch-which-car and
door-safety are both orthogonal to this fix. That containment is exactly
why I'd sequence it before, say, Q9's fleet-partitioning concern (a much
larger, cross-cutting change) or Q18's destination-dispatch rewrite (a
different algorithm family) — it's the highest-correctness-impact,
lowest-blast-radius fix available, which is the right prioritization
heuristic when a new engineer is picking a first task on an unfamiliar
codebase.
