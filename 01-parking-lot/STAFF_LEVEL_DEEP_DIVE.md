# Staff-Level Deep Dive — Parking Lot

These are the questions that separate a correct mid-level design from one
that holds up under staff-level scrutiny: architectural decisions that are
hard to reverse, where the design actually breaks, and what you'd really do
differently at scale or under time pressure. Each answer reasons from the
actual design in `README.md`/`src/`, not generic platitudes.

---

### 1. Two entry gates reserve the same spot at the literal same nanosecond. Walk me through what happens at the instruction level, not just "it's thread-safe."

Both gate threads call `NearestAvailableSpotAllocationStrategy.reserveSpotFor`,
which both independently rank floors and sizes and both arrive at the same
`(floorNumber, size)` bucket — say it's the only bucket with stock. Both
call `InMemorySpotRepository.reserveSpot`, which calls
`candidates.pollFirst()` on the shared `ConcurrentSkipListSet`.
`ConcurrentSkipListSet.pollFirst()` is implemented over a lock-free skip
list with its own internal CAS loop at the node level — it is guaranteed to
hand each distinct element to at most one caller, full stop, regardless of
how the two threads are scheduled relative to each other. So: Thread A gets
spot #1, Thread B gets spot #2 (the next element), or if there's only one
spot in the bucket, one thread gets it and the other gets `null` back from
`pollFirst()` and falls through to the next candidate size/floor. There is
no interleaving of these two calls that can result in both threads
receiving a reference to the *same* `ParkingSpot` object. The subsequent
`candidate.tryReserve()` CAS is actually redundant-but-defensive in the
common case — it exists to protect against a hypothetical future bug where
something else mutates `ParkingSpot.status` outside the index-ownership
discipline, not because the index itself could double-hand-out an element.

---

### 2. Your `InMemorySpotRepository` invariant is "the available index is the sole gate for state transitions." What's the actual proof this invariant holds, and where would it break if someone "innocently" extended the code?

The proof is by construction: scan every call site in the codebase that
transitions a `ParkingSpot` out of `AVAILABLE` (`tryReserve`,
`tryMarkOutOfService`) — both are only ever invoked from inside
`InMemorySpotRepository`, immediately after (for reserve) a successful
`pollFirst()` or (for out-of-service) a successful `bucket.remove(spot)`.
There is no other code path in the entire source tree that calls these
methods directly. The invariant is therefore "true by absence of
counter-examples," which is a real but fragile kind of proof — it holds
only as long as nobody adds a new call site.

Where it would break: if a future engineer added a convenience method like
`ParkingSpot.forceReserve()` bypassing the repository, or if
`ParkingLotService` were refactored to call `spot.tryReserve()` directly
"to save a method call" without going through
`spotRepository.reserveSpot()`, the index could end up out of sync with
reality (a spot reserved but still sitting in the "available" index, which
would let a second thread hand it out again via `pollFirst`). This is the
honest downside of encoding an invariant as "discipline across call sites"
rather than as a single enforced chokepoint — a package-private constructor
on `ParkingSpot` plus making `tryReserve`/`tryMarkOutOfService`
package-private (only callable from within `lld.parkinglot.repository`)
would convert this from a convention into a compiler-enforced guarantee,
and is a change I'd make before shipping this to a larger team.

---

### 3. The process crashes immediately after `PaymentService.pay()` returns successfully but before `Ticket.close()` runs. What state is the system in, and is that acceptable?

In the current in-memory design: unacceptable, and the README says so
directly (Section 13). Everything — the ticket's status, the spot's
status, the payment record — lived only in heap memory, so a crash loses
all of it; on restart there is no payment record, no ticket, the spot shows
as whatever its last-flushed... there is no flush, it's just gone. The
driver was charged (assuming the real payment gateway's charge actually
went through, which is independent of our process) but our system has no
record of it and the spot is never freed by software (would require manual
staff intervention).

This is *correct* behavior to admit, not to hide, in a staff interview —
the fix is not "make ParkingSpot somehow crash-proof," it's architectural:
move to a persistent store (Section 12) and wrap payment-success +
ticket-close + spot-free in one database transaction, so a crash before
commit rolls everything back to "payment never happened" *from the
system's point of view*, even if the gateway's side effect already fired.
That residual gap — gateway succeeded, our transaction didn't commit — is
exactly why the real fix also needs an idempotency key on the gateway call
(so a retry after recovery doesn't double-charge) plus a reconciliation job
that periodically finds "gateway says charged, we have no matching
committed payment" and either completes or refunds. This is the single
most consequential "hard to reverse if not designed for up front" property
of this system once persistence is added — retrofitting idempotency keys
after a payment integration already exists, across every call site that
might retry, is materially harder than building it in from day one.

---

### 4. Add a requirement: "a single ticket can now cover a car + an attached trailer, which must occupy two *adjacent* spots atomically — either both or neither." Can you do this without changing `ParkingSpot`?

No, and this is a genuinely hard extension, which is why it's worth naming
explicitly rather than hand-waving. The entire current concurrency design
rests on each allocation being a *single* atomic operation (one
`pollFirst` + one CAS). Reserving two specific, adjacent spots atomically
is a multi-resource transaction — classic "dining philosophers" territory:
if you reserve spot A then try to reserve spot B, another thread could
reserve B first, and now you must roll back A, but rolling back and
retrying without a canonical lock ordering risks livelock under high
contention.

The correct fix *without* introducing a global lock: assign a strict total
order to every spot (e.g. `(floorNumber, spotNumber)`, which already
exists), and when reserving a pair, always acquire in that order, and use
a bounded-retry-with-backoff loop: try-reserve-lower, then try-reserve-
higher, and if the higher one fails, release the lower one and retry the
whole pair from the start (possibly against a different candidate pair).
This is a "two-phase optimistic" protocol, not a 2PC transaction — it's
still lock-free, but it is a materially more complex allocation strategy
than today's `SpotAllocationStrategy` interface was designed for (which
assumes "reserve one spot" is the whole contract). The honest answer is:
this requires widening `SpotAllocationStrategy`'s contract to
"reserve 1..N compatible, co-located spots," which is a breaking interface
change, not an additive one — exactly the kind of requirement that, if
anticipated from day one, would have shaped the interface differently (one
more reason interface design deserves real up-front thought, not just
"we'll generalize it when we need to").

---

### 5. You chose `ConcurrentSkipListSet` per (floor, size) bucket. At what scale does this stop being the right choice, and what replaces it?

The complexity is O(log n) per reserve/free, where n is spots in one
bucket. For this to become a real bottleneck, a single (floor, size)
bucket would need to hold so many spots that log n no longer fits in cache
— practically, tens of millions of elements in one skip-list bucket, which
has no physical analogue (a real floor has, at most, a few hundred spots
of any one size). So at *garage* scale this structure never becomes the
bottleneck; the honest answer to "when does this stop being right" is "when
the unit of allocation stops being a single physical garage" — e.g. if this
system were repurposed to allocate, say, warehouse pallet slots across an
entire logistics network with millions of slots in one contention domain,
you'd want to shard the allocation domain itself (by warehouse, by zone)
rather than trying to make one data structure hold everything — which is
exactly what this design already does implicitly (bucketing by floor and
size is already a sharding strategy, just one scoped to "one garage").

---

### 6. Why is `ParkingLotService` not itself thread-unsafe, given it calls multiple independently-atomic things in sequence without holding a lock across them?

Because none of its sequencing requires cross-step atomicity to preserve
correctness — each step's *failure* is independently and completely
handled by rolling back only what that step itself did, not by needing to
"undo" a combination of steps as one unit. Concretely: if `tryClaimPlate`
succeeds but `reserveSpotFor` then fails, the *only* side effect that
happened is the plate claim, and `parkVehicle` explicitly undoes exactly
that one thing. There's no scenario where two steps both partially
succeeded in a way that requires compensating both together — the design
was specifically shaped (Section 11.3 race table) so that every step's
rollback is self-contained. This is a direct consequence of designing the
order of operations deliberately (claim before reserve, reserve before
occupy, payment before close) rather than an accident — reordering any two
of these steps would very likely reintroduce exactly the kind of
partial-failure-needs-cross-step-rollback problem this design avoids. I'd
flag this ordering as one of the "hard to reverse" decisions: a future
engineer "simplifying" `parkVehicle` by, say, creating the `Ticket` before
confirming the spot occupy succeeded, would silently break this property.

---

### 7. What's the actual first point of failure if this were deployed to handle 10 million parking events per day across one physical garage?

Ten million events/day is ~115/second sustained, bursting higher at
rush hours — trivial for the in-process lock-free design to handle; this
is not a throughput problem at the CAS/skip-list layer at all. The real
first point of failure is the **single JVM process** itself: no
replication, no failover, in-memory-only state. A single process restart
(deploy, crash, OOM) loses every active ticket and every spot's occupied
status instantly — every parked car's ticket simply vanishes from the
system's point of view, even though the car is still physically in the
garage. The fix is not "make the concurrency faster," it's "make the state
durable and the process restartable without data loss" — i.e. Section 12's
persistence story is the actual prerequisite for handling real sustained
load, not a performance optimization. This is a good staff-level reframing:
the bottleneck question is often not "where is it slow" but "where does it
lose correctness or data first," and for this design that's availability/
durability, not throughput.

---

### 8. Your allocation strategy prefers the smallest sufficient spot size. Does this interact badly with the nearest-floor preference, and can you construct a case where the result feels "wrong" to a user?

Yes. Consider: floor 0 (nearest the entry gate) has only `LARGE` spots
free; floor 5 (far away) has a `SMALL` spot free. A motorcycle arrives.
Today's strategy ranks *floors* first, then sizes within a floor — so it
checks floor 0 first, finds a compatible `LARGE` spot (motorcycles fit
anywhere), and allocates that, even though a `SMALL` spot much farther away
exists. This is "wrong" only if the actual business preference is "always
use the smallest sufficient spot available *anywhere*, even far away" —
which contradicts "nearest spot," since those two preferences are
fundamentally in tension once compatible spots of different sizes exist on
different floors at different distances. The current design resolves the
tension by prioritizing proximity over size-efficiency (floor order is the
outer loop, size is the inner loop) — a defensible choice (most users care
more about walking distance than whether the garage "wastes" a large spot
on their motorcycle), but it is a real trade-off, not a free win, and I'd
say so explicitly if asked rather than claim the current ordering is
strictly better. Swapping the loop order (size outer, floor inner) is a
one-line change in `NearestAvailableSpotAllocationStrategy` if the business
decided proximity should be secondary to size-efficiency — this is exactly
the kind of policy decision the Strategy pattern was meant to isolate.

---

### 9. `Money` uses `BigDecimal`. What's the actual risk of a `double`-based implementation here, concretely, not just "floating point is bad"?

Concretely: `0.1 + 0.2 != 0.3` in IEEE 754 double arithmetic — it evaluates
to `0.30000000000000004`. For a pricing strategy computing
`hourlyRate.multiply(billableHours)`, if `hourlyRate` were `2.50` stored as
a `double`, repeated multiplication/rounding across many tickets can
accumulate visible cent-level discrepancies between what's displayed to a
driver and what's actually charged by a `PaymentProcessor` reading the same
nominal value — and in a real system, reconciliation between "what we
billed" and "what the payment gateway charged" (a very real operational
process) becomes unreliable if the two sides don't agree to the cent, every
time, deterministically. `BigDecimal` with explicit scale (`Money` always
normalizes to 2 decimal places via `setScale(2, HALF_UP)`) guarantees
`Money.of(2.50).multiply(3)` is *exactly* `7.50`, not
`7.499999999999999` or `7.500000000000001` depending on rounding mode
history. This is one of the few places in this design where "obviously
correct" was prioritized over "obviously fast," and I'd defend that trade
unconditionally for anything touching real currency.

---

### 10. Could you add a new vehicle type (say, "oversized truck requiring 2 adjacent LARGE spots") without changing existing code? Be honest about what breaks.

Partially, and the honest boundary matters. Adding the *enum value* itself
(`VehicleType.OVERSIZED_TRUCK`) and deciding it needs `LARGE` spots is a
one-line change to `SpotSize`'s compatibility sets — purely additive,
Open/Closed holds. But "requires 2 adjacent spots" breaks the fundamental
assumption baked into `SpotAllocationStrategy.reserveSpotFor`'s signature
(`Optional<ParkingSpot>` — singular) and into `Ticket` (`ParkingSpot spot`
— singular field). This is *not* additive; it requires widening both types
to support 1..N spots per ticket, which ripples into `ParkingSpot.vacate()`
callers, `AdminService.occupancySnapshot()`'s counting logic (does a
2-spot ticket count as one occupied "stay" or two occupied spots — it's
two spots, straightforward, but worth stating explicitly), and the pricing
strategy (does a 2-spot vehicle pay double, or one combined rate?). I'd
answer this directly rather than claim false generality: today's design
optimizes for "the overwhelmingly common case is one vehicle, one spot,"
and that assumption is load-bearing in several places — a reasonable
scoping decision for an LLD exercise, but one I'd flag as a known
limitation rather than pretend the Strategy pattern alone absorbs it for
free.

---

### 11. You're told you have 2 weeks to ship a simplified version of this to production. What do you cut, and what do you refuse to cut?

Cut: the `ConcurrentSkipListSet` lock-free allocation index — a single
`synchronized` method per floor-bucket is slower under contention but is
far faster to write, review, and reason about correctness for under time
pressure, and at real garage scale (hundreds of spots, tens of gates) the
performance difference is unmeasurable. Cut: flat-rate pricing (ship
hourly only, leave the `PricingStrategy` interface in place so it's
additive later). Cut: multiple payment processors (ship one, keep the
registry pattern so adding more is config, not code).

Refuse to cut: the atomic plate-claim mechanism (`tryClaimPlate`) — double-
booking the same vehicle is a correctness bug a customer will notice
immediately and it costs nothing extra to get right even under time
pressure (it's one `putIfAbsent` call). Refuse to cut: payment-before-
commit ordering in the exit flow — the alternative (free the spot or close
the ticket before confirming payment) is a revenue-leak bug, not a nice-to-
have, and reordering two lines of code later after the wrong order shipped
means tracking down every place that assumed the wrong invariant. Refuse to
cut: at least a basic version of the `ParkingSpot` state machine with
guarded transitions — letting callers set a raw status field directly
"for now, we'll clean it up later" is exactly the kind of shortcut that
becomes permanent and is the root cause of the double-allocation bug class
this design exists to prevent.

---

### 12. Your design has zero singletons and zero static mutable state. Is that a stylistic preference or does it matter for this specific problem?

It specifically matters here because this is a problem about testing
concurrent correctness, and shared static/singleton state is the single
biggest obstacle to writing deterministic, isolated concurrency tests.
Every test in `tests/` constructs a brand-new `InMemorySpotRepository`,
`InMemoryTicketRepository`, etc., per test method — if any of these were
singletons (or backed by static fields), tests would leak state into each
other (a spot reserved in one test still showing reserved in the next),
forcing either manual reset-state boilerplate before every test or, worse,
flaky test ordering dependencies. The concurrency stress tests in
particular (`manyEntryGatesCannotOverbookOrDoubleAllocateSpotsConcurrently`)
depend on starting from a known-clean set of spots to make exact-count
assertions — that's only possible because nothing is global. This is a
concrete example of "dependency injection isn't a style preference, it's a
testability requirement" that's worth being able to defend with a specific
mechanism, not just the word "testability."

---

### 13. The `InMemoryTicketRepository.tryClaimPlate` uses the license plate string itself as the concurrency key. What's the actual risk in that, and would you change it?

Two real risks worth naming. First, license plate strings aren't
normalized for free — `"KA-01-AA-0001"` vs `"ka01aa0001"` vs
`"KA 01 AA 0001"` would be treated as different keys by a raw string map,
silently defeating the duplicate-entry invariant for the same physical
vehicle entered with slightly different formatting (e.g. OCR vs. manual
entry at two different gates). `Vehicle`'s constructor already does
`trim().toUpperCase()` normalization specifically to close this gap before
the plate ever reaches the repository — worth pointing out proactively,
since it's easy to assume the repository handles this and be wrong.
Second, using the plate string directly as a `ConcurrentHashMap` key means
contention/hashing is driven by string hashCode — fine at garage scale
(hundreds of concurrent plates), but if this were ever shared across a
much larger keyspace (multi-garage, millions of plates), I'd consider
whether a more uniformly-distributed key (e.g. a hash) matters for
`ConcurrentHashMap`'s bucket distribution — in practice, Java's String
hashCode is good enough here and I would not change it without a measured
reason to.

---

### 14. What does "consistency" actually mean for this system, formally, and does your design provide it?

I'd state the specific consistency property this system actually needs,
rather than use the word loosely: **at every observable instant, the set
of `OCCUPIED` spots is in 1:1 correspondence with the set of currently-
active tickets, and no license plate has more than one active ticket.**
This is a safety property (something bad — double-allocation or
double-ticketing — never happens), not a liveness property (something good
eventually happens) — and it's the safety property, not liveness, that the
CAS-based design is built to guarantee, because CAS primitives give you
linearizability of individual operations, which composes into this
system-wide invariant *given* the specific sequencing `ParkingLotService`
enforces (claim → reserve → occupy → save, in that order, with rollback on
any step's failure). I would not claim this design provides "strong
consistency" in the distributed-systems sense (there's no replication, no
quorum, it's a single process) — the correct framing is "single-process
linearizable consistency," which is the right and sufficient bar for this
problem, and conflating it with distributed consistency models would be a
red flag in how I communicate about the design, not just a technical gap.

---

### 15. If I asked you to make spot allocation fair (FIFO across competing gates) instead of first-CAS-wins, what's the smallest change that achieves it, and what does it cost?

The smallest change: replace the direct `pollFirst()`+CAS race with a
single-threaded allocator per (floor, size) bucket — i.e. route all
allocation *requests* for a bucket through a bounded queue consumed by one
dedicated thread (or an `ExecutorService` with a single thread per
bucket), so requests are processed in the order they arrive rather than in
whichever order their CAS happens to land. The cost: this reintroduces a
serialization point per bucket (exactly what the lock-free design
currently avoids), and under high contention on one popular bucket, a
request could now wait behind N others even though a different,
equally-valid spot in the *same* bucket might have been free the whole
time if allocation order didn't matter — i.e. you trade throughput and
some allocation flexibility for a strict ordering guarantee. I would not
make this change speculatively; I'd only make it if a concrete requirement
("process requests fairly, measurable by...") existed, because it
directly undoes the main performance property of the current design for a
guarantee most garages don't actually need (Section 3, clarifying question
10, explicitly surfaces this trade-off up front rather than discovering it
mid-implementation).

---

### 16. Your `Ticket.close()` throws if called twice. Is throwing the right behavior, or should a duplicate exit request be idempotent (return the same receipt both times)?

This is a genuine design choice I'd want to make explicitly rather than
default into. Throwing (current behavior) is correct if a "duplicate exit"
is actually an error condition — e.g. a client bug retrying after a
successful response was lost on the network, which from the server's
perspective should surface as "wait, this already happened" so the bug can
be found and fixed. Returning the same receipt idempotently is correct if
retries-after-network-loss are an *expected, routine* occurrence that
clients are designed to handle (e.g. an exit gate's client code
automatically retries on timeout) — in that world, throwing on the retry
would incorrectly tell a legitimate retry "this failed" when it actually
succeeded the first time, which could confuse a driver standing at a gate.
Given this system's actual request path (one physical exit gate, one
driver, synchronous flow, no network between gate and service in the
current scope), throwing is the more honest signal and I'd keep it — but
I'd flag that the moment `ExitGateService` and `ParkingLotService` are
split across a network boundary (a real microservice deployment), I'd
revisit this and likely make `unparkVehicle` idempotent keyed by ticket id,
returning the original receipt on a detected retry rather than throwing.

---

### 17. Why does `AdminService` live in a separate class from `ParkingLotService` instead of just adding two more methods to the facade?

Two independent reasons, and I'd give both rather than just one. First,
Single Responsibility in the literal sense used here: `ParkingLotService`'s
reason to change is "the park/unpark business flow changed"; `AdminService`'s
reason to change is "an operational/maintenance concern changed" — these
are different stakeholders (product vs. operations) with different review
processes in a real org, and coupling them in one class means a change
driven by one stakeholder risks an unintended side effect reviewed by the
wrong team. Second, and more concretely: in a production system, admin
operations need a *materially different authorization model* (only
authenticated staff, likely audited, possibly requiring a second
approval for high-impact actions) versus the park/unpark flow (any vehicle,
no authentication). Keeping them as separate classes means the
authorization *middleware* in a real deployment can be applied at the class
boundary (e.g. "every method on `AdminService` requires a staff JWT") rather
than needing per-method annotations scattered through a single mixed-
responsibility facade.

---

### 18. What's the single hardest-to-reverse decision in this design, and why?

The choice to make `ParkingSpot` the atomic unit of concurrency (one spot
= one independently-lockable thing), rather than, say, a whole `Floor` or
the whole `ParkingLot`. This decision is threaded through everything:
the repository's bucket structure, the allocation strategy's contract
(`Optional<ParkingSpot>`, singular), `Ticket`'s singular `spot` field, and
every concurrency test's assertions. It's the right call for this problem
(Question 4 above shows exactly why — the moment a requirement needs
multi-spot atomic reservation, this assumption has to be unwound in
several places at once, not just one). I'd call this out proactively as
the one decision I'd want a second opinion on before committing to, in any
real design review, precisely because of how expensive it would be to
revisit after the fact — contrast this with, say, the choice of
`ConcurrentSkipListSet` vs. a different sorted structure, which is
genuinely a one-class, no-ripple-effects change if proven wrong later.

---

### 19. How would you actually load-test this design to validate the performance claims in README Section 14, rather than just asserting them?

I would not trust a unit test's "it completed in under N milliseconds" as
a load-test result — JIT warm-up, GC pauses, and CI-runner noise make
single-run wall-clock assertions unreliable. Instead: a JMH
(Java Microbenchmark Harness) benchmark measuring `reserveSpot`/`freeSpot`
throughput under a fixed number of concurrent threads hammering a
fixed-size bucket, reporting ops/sec with proper warm-up iterations
excluded — this gives a defensible number for "how many allocations/sec can
one (floor,size) bucket sustain," which is the actual bottleneck unit in
this design. Separately, a realistic end-to-end load test (e.g. Gatling or
a hand-rolled thread-pool driver) simulating a full day's traffic pattern
(morning rush entry-heavy, evening rush exit-heavy, steady background
rate between) against the full `ParkingLotService`, to validate that
payment-processing latency (the one genuinely slow, I/O-bound step in a
real deployment) doesn't become a queueing bottleneck even though the
in-memory allocation itself is fast — this is the kind of test that would
actually reveal whether Section 14's "payment, not allocation, is where
real-world latency lives" claim is true, rather than just asserting it.

---

### 20. If you had to support "the same parking lot system, but now also used for a 2-floor residential building's limited guest parking with monthly reserved spots," what breaks and what doesn't?

What doesn't break: the core `ParkingSpot` state machine, the allocation
strategy interface, the pricing strategy interface — a "reserved for
resident 4B" spot is just a spot that should never appear in the
nearest-available pool for guests, which is expressible today by simply
not including it in the guest-allocatable bucket (a configuration/data
concern, not a code concern). What does break: the implicit assumption
that every spot is fungible within its (floor,size) bucket — "reserved"
spots need an owner concept this domain model doesn't have at all
(`ParkingSpot` has no notion of a long-term assignee, only a *currently
parked* `Vehicle`). Adding that is a genuinely new entity
(`SpotReservation` or similar, with its own lifecycle independent of a
single stay) and a new allocation rule ("guests get only the
non-reserved subset of a bucket") — again additive at the data-model level
but it does mean `SpotAllocationStrategy`'s candidate-gathering step needs
to filter by "is this spot in the unreserved pool" before ranking by
distance/size, which is a real (if small) change to
`NearestAvailableSpotAllocationStrategy`'s logic, not purely configuration.
I'd flag this as evidence the current design is well-scoped for its stated
problem (a commercial garage with no long-term reservations) and
deliberately not over-generalized to absorb every adjacent use case for
free — which is the correct trade-off for an LLD exercise, but worth being
honest about rather than claiming unlimited extensibility.

---

### 21. Your tests use real threads and `CountDownLatch`, not a model checker or a deterministic concurrency testing tool. What are you *not* catching with this approach?

Real-thread tests with a `CountDownLatch` reliably exercise the common,
likely interleaving (everyone starts "at once") but are fundamentally
non-exhaustive — the JVM thread scheduler won't necessarily explore every
theoretically-possible interleaving in any given run, which means a rare
race that only manifests under one specific, unlikely interleaving could
pass this test suite 1000 times and still harbor a bug. What this approach
specifically *does* catch, reliably: the common double-allocation race,
because with 40-100 threads actually contending in real time, the odds of
*that specific* race manifesting approach certainty in practice (empirically
validated by watching the test fail against a deliberately-broken
check-then-act implementation during development of this design). What it
does *not* catch with confidence: subtle memory-visibility bugs that depend
on specific JIT/CPU memory-model reordering, which is exactly why this
design leans on `AtomicReference`/`ConcurrentHashMap`/
`ConcurrentSkipListSet` — JDK-guaranteed-correct primitives — rather than
hand-rolled `volatile`-field-based synchronization that would need a tool
like JCStress (which deliberately explores adversarial interleavings at the
JIT/CPU level) to validate with real confidence. I'd name JCStress
specifically if asked "how would you get more confidence than this," rather
than imply the current tests are a complete proof.

---

### 22. Is there a simpler architecture than this one that would still satisfy all the stated functional requirements? If so, why didn't you build that instead?

Yes — a single `synchronized` class wrapping one `ArrayList<ParkingSpot>`
with a linear scan for "nearest compatible available spot," guarded by one
lock for every operation, would satisfy every *functional* requirement
in Section 1 identically. It would be meaningfully simpler to read and
reason about for someone unfamiliar with lock-free programming. I didn't
build that as the final answer because the stated non-functional
requirements explicitly include "concurrent spot allocation from multiple
entry gates" (Section 2) framed as a scalability/availability concern, not
just a correctness one — a single global lock technically satisfies
correctness but actively contradicts "don't let gates block each other
unnecessarily," which is the entire point of calling out multi-gate
concurrency as a named requirement rather than an incidental detail. That
said, I'd genuinely offer the single-lock version as the right answer
under the 2-week-ship framing in Question 11 — this is a case where "the
simpler alternative" and "the chosen design" are both legitimate answers
depending on which non-functional requirement is actually binding, and a
staff engineer should be able to argue either side depending on context
rather than being wedded to the more sophisticated one as inherently
better.
