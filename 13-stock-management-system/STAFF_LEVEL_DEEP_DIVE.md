# Staff-Level Deep Dive — Stock Management System

Twenty-plus problem-specific, challenging questions with reasoned (not one-line)
answers, covering architecture reversibility, scaling bottlenecks, failure modes,
consistency guarantees, concurrency correctness, extensibility, API evolution, and
operational complexity.

---

### 1. "Two threads call `reserveForOrder` for the same `orderId` at the same time — say, a retried HTTP request racing the original. Walk me through exactly what happens, and is it correct?"

Both calls resolve the same `InventoryKey` and both call `ledger.reserve(key,
quantity)`. Under any of the three strategies, both calls are individually
atomic and will both succeed if there's enough stock for both — the ledger has no
concept of `orderId` at all, so it cannot detect "this is the same order retried."
The result: `reserved` is incremented by `2 × quantity`, and the order is
double-reserved. This is **not** a concurrency bug in the ledger — the ledger is
doing exactly what it's told, correctly, atomically. It's a missing idempotency
layer above the ledger. The honest answer: this implementation does not solve this
today (documented explicitly in README section 13 and 17); the fix is an
idempotency key check (e.g. "has this `orderId` already produced a committed
reservation?") that must happen with its own atomicity guarantee — most naturally
as a unique constraint on a `(order_id, movement_type)` row in the audit/ledger
table in a real DB, not something `AtomicReference`/`ReentrantLock` alone can fix,
because the race is at the *request* level, not the *counter* level.

### 2. "You picked per-(SKU, warehouse) lock granularity. What's the actual failure mode if that key-space has, say, 50 million entries?"

`ConcurrentHashMap` scales well to tens of millions of entries by itself (segment/
bin count grows dynamically), so the map isn't the bottleneck. The real cost is
per-entry memory: each `AtomicReference<InventoryLevel>` row (optimistic) costs an
`InventoryLevel` object (3 fields + object header, ~32-40 bytes) plus the reference
cell itself; each `PessimisticLockInventoryLedger.Row` additionally embeds a full
`ReentrantLock` object (non-trivial — tens of bytes for its internal AQS state)
*per row*, which at 50M rows is a meaningfully larger footprint than the other two
strategies. This is a concrete, measurable reason to prefer the atomic-CAS strategy
(a single primitive `long` per row, no lock object) at very large key-space scale,
and a reason the pessimistic strategy's per-row lock cost should be called out
explicitly as a trade-off, not just "it's a lock so it's fine."

### 3. "The interviewer says: 'actually, we need reservations to survive a process restart.' What breaks, and what's the minimum change?"

Everything breaks — this design is entirely in-memory; a restart loses all
`available`/`reserved`/audit state. The minimum change is *not* a redesign: swap
`InventoryLedger`'s implementation for one backed by the SQL schema in README
section 12 (same interface, same method contracts), and swap `AuditTrail` for a
DB-backed implementation. `InventoryService` requires zero changes because it only
depends on interfaces. This is the direct payoff of having drawn the Repository/
Strategy boundaries where they are — the question is really testing whether the
candidate accidentally coupled the service to `ConcurrentHashMap` internals
anywhere (it doesn't — verify by reading `InventoryService`, which only ever calls
interface methods).

### 4. "Prove to me that `AtomicCasInventoryLedger` is actually correct — not just 'it compiles and the test passed once.'"

The correctness argument is about `AtomicLong.compareAndSet`, which Java guarantees
is backed by a single hardware CAS instruction with full ordering (it has the
memory effects of both a volatile read and a volatile write on success). For
`reserve`: the method reads `current` (a consistent snapshot of *both* packed
fields, since they live in one word), validates `available >= quantity` against
that exact snapshot, computes `updated`, and only commits via
`compareAndSet(current, updated)`. If any other thread modified the row between the
read and the CAS, `current` no longer matches the live value, the CAS fails by
definition, and the loop retries against a fresh read — so the validation is always
checked against the data the write is actually based on, never stale data. This is
why the proof-by-test (50 threads × 40 attempts against 500 units, asserting
exactly 500 successes and `available == 0` at the end, run repeatedly without
flakiness) is meaningful evidence, not just a coincidence — it's stress-testing
exactly the race the algorithm is designed to close.

### 5. "What if the interviewer asks you to add a `STOCK_OUT` (shrinkage/damage write-off) operation right now, live, without changing existing code?"

Trace the dependency chain: `MovementType` already has a `STOCK_OUT` enum value
(added proactively — see `model/MovementType.java` — a minor inconsistency to
flag: it exists in the enum but `InventoryService` doesn't yet expose a method
using it). Adding it cleanly: add `InventoryService.stockOut(sku, warehouse, qty,
reason)` that calls `ledger.adjust(key, -qty)` (an existing ledger method — no
ledger change needed) and records a `STOCK_OUT`-typed movement instead of
`ADJUSTMENT`. Zero changes needed to `InventoryLedger`, any of its three
implementations, or any existing test. This is the Open/Closed payoff in action —
new behavior added by using existing seams, not editing them.

### 6. "Your reorder-point automation uses a static threshold. What's the first thing that breaks when a staff engineer tries to make it seasonal (e.g. different reorder points in December)?"

Nothing breaks structurally — `ReorderAutomationListener.registerPolicy` already
takes a `ReorderPolicy` per key and can be called again to replace it (it's a plain
`Map.put`, not `putIfAbsent`). The real gap is operational, not structural: there's
no mechanism in this repo to *trigger* a policy change on a schedule (no scheduler
dependency, deliberately, per scope) — that's an external cron/job calling
`registerPolicy` with new values. The debounce flag (`requestAlreadyRaised`) is
keyed only by `InventoryKey`, not by policy version, so if a policy change happens
while a key is already below the old threshold but above the new one, the flag
could be stale (e.g. a request was already raised under the old, more lenient
policy, and won't fire again under a stricter one until the level moves). This is a
genuine, documented edge case worth raising proactively, not papering over.

### 7. "Walk me through what happens if a crash occurs exactly between `ledger.reserve()` returning successfully and `auditTrail.record()` being called."

Looking at `InventoryService.recordAndNotify`'s caller sites: every mutation method
calls `ledger.X(...)` first, assigns the result, *then* calls
`recordAndNotify`. If the process crashes in that narrow window, the ledger's
in-memory state already reflects the reservation (since it's the same process's
memory — there's no network hop between them), but since the whole process just
died, **both** the ledger state and the audit entry are lost together — there is no
scenario in a single-process in-memory design where one survives and the other
doesn't, because they share the same failure domain (process memory). This
changes completely once the ledger and audit trail live in a real, possibly
different, database or service: then this becomes a genuine distributed-
transaction problem (the classic "dual write" problem), and the fix is either a
single DB transaction wrapping both writes (if they're in the same database) or an
outbox pattern (if they're not) — explicitly called out as a production concern,
not applicable to the current in-memory design's actual failure mode.

### 8. "You chose synchronous Observer notification. What's the actual blast radius if `LowStockAlertListener`'s sink throws?"

`StockEventListener.onStockEvent` is called directly inside
`InventoryService.recordAndNotify`, with no try/catch around the loop. If any
listener throws, the exception propagates out of the mutation method (e.g.
`reserveForOrder`) to the original caller — meaning **the ledger mutation has
already committed and the audit record has already been written**, but the caller
receives an exception as if the call failed. This is a real, sharp edge documented
in the exception table (README section 13): a caller that catches broadly and
"retries on any exception" could double-reserve, because from the ledger's
perspective the first reservation already succeeded. The fix, if this became a real
risk, is either: (a) isolate each listener in its own try/catch-and-log so listener
failures never affect the caller's view of mutation success, or (b) make
notification fully asynchronous (fire onto a queue, never on the caller's thread)
— both are documented production evolutions, deliberately not implemented, because
this repo's design intentionally lets a listener bug be loud during development
rather than silently swallowed.

### 9. "At 10 million SKU×warehouse combinations with a typical web-scale read:write ratio, where does this design fall over first?"

Not in lock granularity (already per-key) and not in `ConcurrentHashMap` (scales to
that order of magnitude). The first real wall: **per-row memory footprint times key
count**. At 10M rows, the pessimistic strategy's per-row `ReentrantLock` objects
alone could add tens of megabytes of pure lock-state overhead that the atomic-CAS
strategy's packed `long` avoids entirely (see Q2) — a concrete, quantifiable
argument for strategy choice at this scale, not just an abstract preference. The
second wall, arriving sooner in practice: unbounded audit-trail growth in a
`CopyOnWriteArrayList` per key — at high movement velocity on hot SKUs, each append
copies the entire list (`O(n)` per append), so audit history for an extremely
"movement-heavy" SKU (think: a SKU reserved/released thousands of times a day)
degrades badly over time; this is exactly why section 17 calls out audit retention/
archival as a first production concern, not an afterthought.

### 10. "If you had two weeks to ship *something*, what would you cut from this design, and is that a safe cut?"

Cut, in order: (1) two of the three ledger strategies — ship only the pessimistic
one (simplest to reason about correctness for, in a time-constrained team, even
though it's not the fastest); (2) the lot/FEFO extension entirely (it's explicitly
optional and orthogonal); (3) the reorder-automation debounce sophistication —
ship a simpler "alert every time below threshold" for both low-stock and reorder,
accepting some purchasing-team noise, and add debouncing once it's a measured
annoyance rather than a guessed one. **Not safe to cut**: the no-overselling
invariant itself, and the audit trail (both are the kind of correctness/compliance
property that is far more expensive to retrofit than to build in from day one —
exactly the "hard to reverse" category this question is probing for).

### 11. "Which architectural decision in this design would be hardest to reverse later, and why?"

The choice to model `available`/`reserved` as two separate numbers per key, rather
than a single number with external reservation tracking elsewhere (e.g. a separate
reservations table/ledger referencing orders). The two-counter model is simple and
fast (O(1) reserve/release/fulfill), but it means the ledger has no memory of
*which* order holds *which* portion of `reserved` — only the aggregate. If a future
requirement needed "show me exactly which orders are currently holding reserved
stock for this SKU" (a very plausible ask — customer support debugging "why is this
out of stock"), that information doesn't exist in the ledger at all; it would have
to be reconstructed from the audit trail (scanning `RESERVE`/`RELEASE_RESERVATION`/
`FULFILL_RESERVATION` movements by `referenceId`), which works but is a reporting
query, not an O(1) lookup, and the ledger itself would need a structural change (a
per-order reservation table) to support this efficiently at scale. This is flagged
explicitly because it's the kind of decision made for good reasons (performance,
simplicity) that is genuinely expensive to walk back.

### 12. "Your invariant list says `onHand = available + reserved` is conserved across reserve/release/fulfill. Is that actually provable from the code, or just asserted?"

It's provable by inspection, not just claimed: look at `reserve` in each ledger —
every single implementation computes `available - quantity` and `reserved +
quantity` from the *same* read of the current state, in the *same* atomic unit (one
CAS, one lock-held critical section). Algebraically, `(available - quantity) +
(reserved + quantity) == available + reserved` — the quantity cancels out by
construction, not by convention. The same check applies to `releaseReservation`
(uses `released = min(quantity, reserved)` symmetrically on both sides). This is
exactly the kind of claim that should never be taken on faith in a design review —
and in this case it holds up under direct inspection of all three strategy
implementations, which is itself a point worth making explicitly to an interviewer:
"here's why I can say that with confidence, not just assert it."

### 13. "What's the actual (not hand-waved) throughput ceiling of the pessimistic strategy on one hot key, and how would you measure it?"

The ceiling is bounded by lock acquisition/release overhead plus the (tiny)
critical section work, serialized — i.e. throughput on one key is roughly `1 /
(critical_section_time + lock_overhead)`, and it does **not** improve by adding more
threads past the point of contention (more threads just queue longer; `ReentrantLock`
under contention parks waiting threads, so you get correct but not faster behavior).
To actually measure this (not just claim it), you'd run a JMH (Java Microbenchmark
Harness) throughput benchmark with an increasing thread count hammering the same
key, and expect a flat-then-declining throughput curve past the point where threads
outnumber what the critical section can drain — contrasted with the optimistic/CAS
strategies, which would show a similar flattening but via wasted-retry CPU instead
of blocked-thread parking, which is a different cost profile worth actually
graphing side by side if this were a real capacity-planning exercise.

### 14. "The optimistic ledger has a `maxRetries` of 1000 by default. Is that number defensible, or arbitrary?"

It's a pragmatic default, not derived from a formula, and that should be stated
plainly rather than defended as if it were scientifically chosen. The honest
reasoning: 1000 retries of a cheap read+compute+CAS (nanoseconds to low
microseconds each under real contention) bounds worst-case latency to roughly
single-digit milliseconds even under heavy contention, which is acceptable for an
interactive request path, while still being high enough that it would essentially
never trigger under realistic load (the concurrency test uses 50 threads and never
approaches exhaustion). The actually defensible position is: this number should be
a tuned, monitored configuration parameter in production (with
`ConcurrencyRetryExhaustedException` rate as the metric that tells you whether it's
set correctly), not a constant baked into the class — which is exactly why the
constructor exposes it as a parameter rather than hardcoding it.

### 15. "Why didn't you make `InventoryLevel`'s `version` field meaningful for the atomic-CAS strategy, and is that a problem?"

`AtomicCasInventoryLedger.toLevel` always passes `0L` for version — this is a
deliberate, documented simplification (noted in the class's trade-off discussion):
the packed-long approach has no spare bits for a version counter alongside the two
32-bit fields, and the strategy doesn't need one for its own correctness (its
correctness comes from CASing the whole packed state, not from a version check).
Is it a problem? Only if some caller actually depends on reading a meaningful
version from this specific strategy — and no current caller does (`InventoryService`
never reads `.getVersion()`). This is a legitimate interface-conformance cost of the
Strategy pattern: all three implementations share one interface shape
(`InventoryLevel` with a `version` field) even though version is only *meaningful*
for one of them. Flagging this honestly beats pretending it's not a real seam.

### 16. "If I added a fourth ledger strategy using database row versioning for this exact in-memory exercise, would that even make sense?"

No — that would be conflating two different problems. Database row versioning
(the SQL mapping in README section 12) *is* the same conceptual strategy as
`OptimisticVersionedInventoryLedger`, just at a different persistence layer; adding
it as a fourth *in-memory* strategy would just be a renamed duplicate with no new
concurrency-control idea behind it. The honest answer to "should there be a fourth
strategy" is: only if it represents a genuinely different *mechanism* (e.g. a
software-transactional-memory approach, or a sharded-counter approximate-count
strategy for extreme write throughput at the cost of exact consistency) — not
simply moving an existing mechanism to a different storage medium.

### 17. "You said lock-ordering deadlocks are 'structurally impossible' because no operation holds two locks at once. What's the first future requirement that would break that claim?"

Any operation that must be atomic *across two different `InventoryKey`s at once*
— the canonical example is an inter-warehouse stock transfer that must debit one
warehouse's available stock and credit another's as a single atomic unit (so a
crash mid-transfer can't lose or duplicate stock). The moment that requirement
exists, a naive implementation (lock warehouse A, then lock warehouse B) introduces
a classic lock-ordering deadlock risk if two concurrent transfers go in opposite
directions (A→B and B→A simultaneously). The documented mitigation (README section
11) is a total lock ordering discipline (e.g. always acquire the lexicographically
smaller `InventoryKey`'s lock first), which is correct but adds real complexity —
exactly why this repo calls it out as "not currently needed" rather than building
it speculatively.

### 18. "Does the low-stock alert / reorder-request asymmetry actually hold up, or is it just a nice-sounding design note?"

It holds up under direct code inspection: `LowStockAlertListener.onStockEvent` has
no suppression state at all — it evaluates the threshold fresh on every event and
fires every time the condition holds, by construction (no flag, no map, nothing to
reset). `ReorderAutomationListener.onStockEvent`, by contrast, explicitly uses
`requestAlreadyRaised.putIfAbsent` to suppress repeats and only `.remove()`s the
flag once the level climbs back above the reorder point. These are two different,
intentional code paths, not an accidental inconsistency — and the demo (`Main`)
actually exercises this exact asymmetry (two alert firings vs. one reorder request
across the same sequence of events in the sample run), which is observable, not
just asserted in prose.

### 19. "What would change about your answer if this were being built as a Kafka-consumer-driven system instead of direct method calls?"

The ledger/concurrency-control discussion is largely unaffected — a Kafka consumer
processing "reservation requested" events still needs the same no-overselling
guarantee for concurrent partitions/consumers touching the same key, so the same
three-strategy comparison applies (most naturally mapped onto the DB-transaction
forms in section 12, since a Kafka consumer is usually backed by a real database
for durability anyway). What *would* change significantly: the Observer pattern's
synchronous-notification design (section 11/17) becomes largely moot, since
Kafka itself *is* the asynchronous event bus — `StockEventListener` would likely be
replaced by independent downstream consumers of a "stock event" topic rather than
in-process listeners, and idempotency (Q1) becomes mandatory rather than optional,
since Kafka's at-least-once delivery semantics guarantee duplicate message
delivery under rebalances/retries.

### 20. "You claim per-key operations are linearizable but cross-key operations are not serializable. Demonstrate a concrete scenario where that distinction actually matters to a user."

Scenario: a customer's cart contains SKU-A and SKU-B, both from the same
warehouse, both with exactly 1 unit left. Two concurrent checkouts both call
`reserveForOrder` for SKU-A then SKU-B, for different carts. Each individual
`reserveForOrder` call is linearizable (one succeeds, one gets
`InsufficientStockException`, per key, correctly) — but there is no guarantee that
"checkout 1 gets both A and B" or "checkout 2 gets both A and B" as a unit; it's
entirely possible checkout 1 wins SKU-A and checkout 2 wins SKU-B, leaving both
carts with a partial, unfulfillable order. This is the real-world meaning of "no
cross-key atomicity" — it's not an abstract caveat, it directly produces the
"split cart" problem familiar from e-commerce. The documented mitigation (out of
scope for this exercise, called out in README section 16) is an explicit multi-key
reservation operation that locks/CASes all keys for a cart as a single transaction
— which reintroduces exactly the lock-ordering-deadlock concern from Q17 and is
precisely why it isn't built here without being asked for.

### 21. "Final one: if this had to go from a demo to production-ready in two sprints, what's the single highest-leverage change you'd make first, and why that one over the others?"

Replace the in-memory `InventoryLedger`/`AuditTrail`/repositories with database-
backed implementations using the optimistic-version-column SQL mapping (README
section 12) — ahead of idempotency keys, ahead of async observers, ahead of
distributed locking. Reasoning: durability is the one property that, if missing,
makes every other correctness guarantee meaningless the instant the process
restarts (a perfectly-proven no-overselling invariant is worthless if a crash
simply erases the stock counts). It's also the change that *unlocks* the others
cheaply: idempotency keys need a durable uniqueness constraint to be meaningful;
reservation-TTL reconciliation needs durable state to reconcile against. Every
other production-evolution item in section 17 is easier, not harder, once this one
lands first — which is the actual test of "highest leverage," not just "most
important in isolation."
