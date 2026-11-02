# Interview Playbook — Parking Lot

A speakable, time-boxed guide for running this design live in a 30/45/60
minute round. Pair this with `README.md` for the full technical depth; this
file is about *sequencing and delivery*.

---

## 1. How to Open the Discussion

Don't start designing immediately. Spend 60-90 seconds restating the
problem in your own words and naming the shape of the system, then move
straight into clarifying questions:

> "So we're designing the software behind a multi-floor parking garage —
> vehicles enter through one of several gates, get assigned a spot based on
> size, get a ticket, and later exit through possibly a different gate,
> pay, and free the spot. Before I start modeling, I want to nail down a
> handful of things that materially change the design — can I ask a few
> quick questions?"

This signals you understand scope *and* that you treat requirements as
something to extract, not assume.

---

## 2. First 5 Clarifying Questions to Ask

(Full list with rationale in `README.md` Section 3 — these are the five
that most change the design, in priority order.)

1. "Can a smaller vehicle use a larger spot if that's all that's free, and
   should the system prefer not to waste large spots on small vehicles?"
2. "Is pricing hourly, flat-rate, or do we need to support both?"
3. "Can the same license plate have two active tickets at once — i.e. is
   there any scenario where double-entry is legitimate?"
4. "What should happen when the garage is full — reject outright, or do we
   need a queue/waitlist?"
5. "Are there multiple entry/exit gates, possibly on different floors, and
   does 'nearest spot' mean nearest to the specific gate used?"

---

## 3. Requirements to Establish

State these back explicitly before writing any code, so the interviewer
can correct you early:

- 3 vehicle types, 3 spot sizes, a fit rule between them.
- Multiple entry and exit gates, used concurrently.
- Nearest-available-spot allocation.
- Ticketing with entry/exit timestamps.
- At least two pricing models (hourly, flat), swappable.
- At least two payment methods, swappable.
- Admin can disable/enable individual spots.
- Must be thread-safe under concurrent gate usage — say this requirement
  out loud yourself if the interviewer doesn't raise it; it's the part of
  this problem that separates a mid-level answer from a staff-level one.

---

## 4. How to Identify Core Entities

Walk from nouns in the problem statement to candidate classes, then
immediately classify each as value object vs. entity vs. service:

> "Vehicle and Gate are both just immutable facts — a vehicle is identified
> by its plate, a gate by its id and location — so those are value objects.
> ParkingSpot is the interesting one: it has identity, and its *state*
> changes over time and is contended by multiple threads, so that's my
> entity with real lifecycle. Ticket and Payment are also entities — each
> represents one specific stay/charge, created once, and (for Payment)
> never mutated again."

Then name the services *after* the entities, framed as "things that
coordinate the entities, not things that own state themselves":
ParkingLotService, PaymentService, AdminService.

---

## 5. How to Explain Class Relationships

Use containment language, not just arrows:

> "ParkingLot owns Floors, which own ParkingSpots — that's composition, a
> spot can't exist outside its floor. A spot *references* whichever Vehicle
> is currently in it, but doesn't own that vehicle — the vehicle object is
> just as valid before it parked and after it leaves. A Ticket references
> one vehicle and one spot but owns neither — it's a record of a stay, not
> a container."

This is also the moment to mention you deliberately avoided a `Vehicle`
class hierarchy (Car/Motorcycle/Bus subclasses) in favor of an enum +
compatibility table, and *why* — see Section 16 of the README for the
one-paragraph version to paraphrase live.

---

## 6. How to Draw the Architecture

Draw bottom-up on the whiteboard/doc, narrating as you go:

1. Domain model box at the bottom: Vehicle, ParkingSpot, Floor, ParkingLot, Ticket, Payment.
2. Strategy interfaces above it: SpotAllocationStrategy, PricingStrategy, PaymentProcessor.
3. Repository interfaces beside the strategies: SpotRepository, TicketRepository, PaymentRepository.
4. One orchestration box above both: ParkingLotService (+ PaymentService, AdminService).
5. Thin gate adapters on top: EntryGateService, ExitGateService.

> "Everything flows downward in terms of dependency — gates depend on the
> service, the service depends on strategy and repository *interfaces*,
> never concrete classes. That's what lets me swap in a database-backed
> repository or a new pricing model later without touching orchestration
> code."

(See `diagrams/component-diagram.md` for the exact version to reproduce.)

---

## 7. Which Interfaces to Create First

In this order, and say why:

1. `SpotAllocationStrategy` — the allocation policy is the crux of the
   problem ("nearest available spot"), so nail its contract first.
2. `PricingStrategy` — second most load-bearing requirement (hourly vs. flat).
3. `SpotRepository` — needed to even describe what "available" means to
   the allocation strategy.
4. `PaymentProcessor` / `TicketRepository` — these support the exit flow,
   which is structurally simpler than entry once allocation is solved.

> "I'll define the interfaces before any implementation, because the
> interfaces *are* the design decisions — the implementations are just
> details I can swap."

---

## 8. Which Classes to Implement First

1. `Vehicle`, `Gate` (trivial immutable value objects — get them out of the way).
2. `ParkingSpot` — explain its state machine on the whiteboard *before*
   writing code; this is the highest-value 3 minutes of the whole
   interview.
3. `Floor`, `ParkingLot` (structural, quick).
4. `InMemorySpotRepository` — this is where you demonstrate the
   index-as-sole-gate concurrency design.
5. `NearestAvailableSpotAllocationStrategy`.
6. `Ticket`, `InMemoryTicketRepository` (the plate-claim mechanism).
7. `ParkingLotService.parkVehicle` — wire it all together, narrate the
   rollback-on-failure logic.
8. If time remains: `unparkVehicle`, pricing, payment.

---

## 9. Which Design Patterns to Introduce and When

Introduce a pattern at the moment its *need* becomes visible, never before:

- **Strategy** — the instant the interviewer confirms "we need both hourly
  and flat pricing" (or "multiple payment methods"), say "this is exactly
  what the Strategy pattern is for" and define the interface then.
- **Repository** — introduce when you're about to write
  `NearestAvailableSpotAllocationStrategy` and realize it needs to ask
  "what's available" without caring how that's stored.
- **Builder** — only mention this if asked how you'd construct a
  `ParkingLot` with many floors/spots; don't lead with it, it's a minor
  convenience, not a core design decision.
- Explicitly say what you're **not** using and why if time allows: "I'm not
  making this a Singleton — whoever wires up the app owns one instance, a
  Singleton would just add global state with no benefit."

---

## 10. How to Discuss Concurrency

This is the section that separates staff-level answers. Structure it as:
**name the shared state → name the race → name the fix → prove it.**

> "The shared mutable state here is narrow: a spot's status, the
> available-spot index, and the set of currently-parked license plates.
> Everything else — Vehicle, Gate, Money — is immutable, so it's
> automatically thread-safe.
>
> The race I actually care about: two entry gates racing for the same
> nearest spot. I'm not using a lock for this — I'm using
> `AtomicReference.compareAndSet` on the spot itself, gated by removing it
> from a `ConcurrentSkipListSet` first. Whoever successfully removes the
> spot from that index is the only thread that can possibly transition its
> state next — so the index removal *is* the atomic claim, and I never need
> a lock that spans 'look up a candidate' and 'claim it' as two separate
> steps.
>
> I'd verify this isn't just a reasonable-sounding claim by writing a test
> that starts e.g. 100 threads off a `CountDownLatch` all racing for the
> same bucket, and assert the number of successful allocations exactly
> equals the number of spots — not just 'no exception was thrown'."

If asked "what if two different kinds of operations race — an admin
disabling a spot and a gate allocating it at the same instant":

> "Same mechanism. Both paths go through removing the spot from the same
> available-spot index; whichever operation's removal succeeds first owns
> the subsequent transition, the other one observes it failed and reports
> that naturally — the admin gets told the spot was just taken, the gate
> tries the next candidate."

---

## 11. How to Discuss Edge Cases

Rehearsed, in priority order if time is short:

1. Garage full → clean rejection, not a crash; plate claim rolled back so
   retry is possible.
2. Duplicate entry for the same plate → atomic claim via `putIfAbsent`,
   second caller rejected cleanly.
3. Payment fails at exit → ticket/spot are left exactly as before the
   exit attempt (not half-closed) — "I sequence payment *before*
   committing the close/free, specifically so a failure is a no-op, not a
   partial corruption."
4. Admin tries to disable an occupied spot → rejected, can't evict a
   parked vehicle.
5. (If probed) Process crash between successful payment and
   ticket-close/spot-free — acknowledge this as a real gap in the
   in-memory version and describe the DB-transaction + idempotency-key fix
   from README Section 13/17 rather than pretending it's already solved.

---

## 12. How to Handle Interviewer Interruptions

- **"What if we also needed to support X?"** — answer in terms of which
  seam absorbs it: "That's a new `PricingStrategy` implementation, zero
  changes to `ParkingLotService`." Don't improvise a redesign live unless
  asked to.
- **"Why not just use a lock here?"** — don't get defensive; explain the
  trade-off honestly: "A lock would be simpler to write and is a completely
  valid choice at this scale. I went lock-free because it removes any
  possibility of contention between unrelated floors/sizes and there's no
  deadlock risk to reason about — but if this were a 10-minute exercise I'd
  happily start with a `synchronized` method per spot and mention the
  lock-free version as a follow-up optimization."
- **"I don't think that invariant actually holds"** — re-derive it out loud
  rather than asserting it again more firmly. If they're right, say so and
  fix it; this is a strong positive signal, not a failure.
- **Time check interruption ("we have 10 minutes left")** — immediately
  jump to Section 13 below rather than trying to finish whatever you were
  mid-sentence on.

---

## 13. How to Prioritize if Time Is Running Out

Drop, in this order, and say out loud that you're dropping them:
1. Flat-rate pricing (keep only hourly) — mention the Strategy seam exists
   for it, don't implement it.
2. Admin out-of-service flow — mention the design, skip the code.
3. Multiple payment methods — implement one (`CashPaymentProcessor`),
   mention the registry pattern for others.
4. The `ParkingLotBuilder` — construct a tiny `ParkingLot` by hand in a
   test/demo instead.

Never drop: the `ParkingSpot` state machine, the allocation strategy, and
at least one concurrency-correctness statement (even just verbally, if
there's no time to write the test).

---

## 14. Three Full Walkthroughs with Realistic Timelines

### 30-minute version
- 0-3 min: restate problem, ask the 5 clarifying questions (compressed to 3 if needed).
- 3-6 min: state core entities + relationships out loud, sketch component diagram.
- 6-10 min: define `SpotAllocationStrategy`, `PricingStrategy`, `SpotRepository` interfaces.
- 10-20 min: implement `ParkingSpot` (full state machine), `InMemorySpotRepository.reserveSpot`, `NearestAvailableSpotAllocationStrategy`.
- 20-26 min: implement `ParkingLotService.parkVehicle` only (skip unpark/payment code, describe it verbally).
- 26-30 min: concurrency discussion (Section 10 script above) + Q&A.

### 45-minute version
- 0-4 min: restate + clarifying questions.
- 4-8 min: entities/relationships + component diagram.
- 8-14 min: core interfaces (allocation, pricing, repository, payment).
- 14-28 min: `ParkingSpot`, `InMemorySpotRepository`, allocation strategy, `ParkingLotService.parkVehicle` with rollback.
- 28-36 min: `unparkVehicle` + one pricing strategy + one payment processor.
- 36-41 min: concurrency deep dive with the three-race table from Section 10.
- 41-45 min: edge cases + trade-offs Q&A.

### 60-minute version
- 0-5 min: restate + clarifying questions, explicitly list functional/non-functional requirements.
- 5-10 min: entities, relationships, invariants (say all 9 invariants from README Section 5 out loud at least in summary).
- 10-16 min: full component diagram + interfaces.
- 16-34 min: full entry flow implementation (`ParkingSpot`, repository, strategy, service, rollback).
- 34-44 min: full exit flow (pricing, payment, ticket close, spot free) + admin out-of-service.
- 44-52 min: concurrency deep dive, including the admin-vs-allocation race and a sketch of the concurrency test.
- 52-58 min: persistence (how this maps to a DB schema), failure scenarios (payment-gateway idempotency gap).
- 58-60 min: trade-offs recap + what you'd add for production.

---

## 15. What I Would Say to the Interviewer

Natural, speakable lines for the moments that matter most:

- Opening: "Before I dive in, a parking lot has a few variations that
  change the design a lot — multi-size spots, multi-gate concurrency,
  pricing model — so let me ask a few quick questions first."
- Justifying the state machine: "I want `ParkingSpot` to only ever be
  reachable through legal transitions, so I'm going to make every
  transition a guarded method on the spot itself rather than letting
  callers poke at a status field directly."
- Introducing concurrency: "This is the part I want to spend real time on,
  because 'make it thread-safe' is easy to say and easy to get subtly
  wrong — I'd rather show you the specific race and the specific fix than
  wave my hands at it."
- When proposing lock-free CAS instead of a lock: "I'm deliberately not
  using a lock here — not because locks are bad, but because this
  particular operation already has an atomic primitive that does exactly
  what I need, so a lock would just be slower for no extra safety."
- When asked about the database: "I kept this in-memory on purpose — the
  interesting part of this problem is the concurrent in-process state
  machine, not the SQL. I can sketch the schema this maps to if that's
  useful, but I wouldn't start there."
- When an edge case is raised you hadn't considered: "Good catch — let me
  think through that... [actually think, out loud, for a few seconds]...
  I think the fix is [X], here's why."
- Closing if time allows: "If I were shipping this for real, the two things
  I'd want to add immediately are persistence with a real transaction
  boundary around the exit flow, and an idempotency key on the payment
  call — everything else in this design is already structured to make
  those additive, not a rewrite."
