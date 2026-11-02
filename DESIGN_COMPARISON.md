# Cross-Problem Design Comparison

This document compares *common design decisions* across the 17 problems in this
repo, with concrete cross-references into each problem's own README. The goal
is not to repeat each problem's write-up, but to put them side-by-side so the
*pattern of decision-making* — not just the decision — becomes visible. Every
section ends with an explicit **"when to reuse / when not to"** call, because
pattern-matching a solution from one problem onto a superficially similar one
is the single most common Staff-interview mistake this repo is designed to
inoculate against.

---

## 1. Strategy pattern: seven independent applications

| Problem | What varies | Interface | README reference |
|---|---|---|---|
| Parking Lot | Spot allocation policy, pricing model, payment method | `SpotAllocationStrategy`, `PricingStrategy`, `PaymentProcessor` | [§7](01-parking-lot/README.md#7-design-patterns) |
| BookMyShow | Pricing (fixed vs. demand/surge) | `PricingStrategy` | [§7](04-bookmyshow/README.md#7-design-patterns) |
| Food Delivery | Delivery-partner assignment (nearest vs. round-robin) | `DeliveryPartnerAssignmentStrategy` | [§7](08-food-delivery-system/README.md#7-design-patterns) |
| Splitwise | Expense split math (equal/exact/percent) | `SplitStrategy` | [§7](09-splitwise/README.md#7-design-patterns) |
| Meeting Scheduler | Recurrence expansion (daily vs. weekly) | `RecurrenceExpansionStrategy` | [§7](10-meeting-scheduler/README.md#7-design-patterns) |
| Snake and Ladder | Dice behavior, win condition | `Dice`, `WinCondition` | [§7](11-snake-and-ladder/README.md#7-design-patterns) |
| Elevator System | Car-selection scheduling (SCAN vs. naive FCFS) | `SchedulingStrategy` | [§7](15-elevator-system/README.md#7-design-patterns) |
| Stock Management | Concurrency-control implementation itself, and lot allocation (FEFO) | `InventoryLedger`, `LotAllocationStrategy` | [§7](13-stock-management-system/README.md#7-design-patterns) |
| Slack Chat | Message fan-out (on-write vs. on-read) | `ChannelDeliveryStrategy` | [§7](16-slack-chat/README.md#7-design-patterns) |
| Google Drive | Permission resolution, content hashing | `PermissionResolutionStrategy`, `HashingStrategy` | [§7](17-google-drive/README.md#7-design-patterns) |

**The common thread.** In every case, two or more *concrete, currently-existing*
implementations are shown in the repo (not just "this interface could have a
second implementation someday") — e.g., Food Delivery ships both
`NearestAvailablePartnerStrategy` and `RoundRobinPartnerStrategy`; Stock
Management ships all three locking strategies side by side specifically to
compare them; Elevator ships both `NearestCarScanStrategy` and
`NaiveFcfsStrategy`. Strategy earns its place here because the *comparison
itself* is part of the deliverable, not a hypothetical.

**When a simple if/else would have sufficed instead.** Every README that uses
Strategy also states the rejected alternative, and the common rejection reason
is **Open/Closed**: a `switch`/`if-else` works until a 3rd case needs adding,
at which point you're editing a shared, already-tested class instead of adding
one new class. The repo is equally disciplined about **not** reaching for
Strategy when there are exactly 2 fixed, never-growing cases with trivial
bodies — e.g., Food Delivery explicitly rejects a `Strategy` for order
construction (dine-in vs. delivery) because there's no polymorphic creation
logic yet, only a handful of constructor args (see
[Food Delivery §7, "deliberately not used"](08-food-delivery-system/README.md#7-design-patterns)).

**Don't-reuse example.** Splitwise's `SplitStrategy` (equal/exact/percent) and
Elevator's `SchedulingStrategy` (nearest-car-scan) are both "Strategy", but they
are *not* interchangeable templates: Splitwise's strategies are pure, stateless
functions over a fixed list of participants (no shared mutable state, no
concurrency concern inside the strategy itself), while Elevator's strategy
reads **live, concurrently-mutating** car state (`currentFloor`, `upStops`) to
make its decision. Copy-pasting Splitwise's "stateless pure function" mental
model onto the Elevator scheduler would miss that the strategy itself has to
be written against a documented *snapshot* contract
([§10](15-elevator-system/README.md)) to avoid reading torn state — the two
problems look alike at the interface level but have a materially different
concurrency contract underneath.

---

## 2. State pattern: four lifecycle machines, compared

| Problem | States | Mechanism | README reference |
|---|---|---|---|
| Vending Machine | Idle, HasMoney, Dispensing, OutOfStock, Maintenance (5) | Full GoF State — one class per state, `VendingMachineState` interface | [§7](03-vending-machine/README.md#7-design-patterns) |
| Food Delivery (Order) | 8 statuses, asymmetric legal transitions | Full GoF State — one class per status under `state/` | [§7](08-food-delivery-system/README.md#7-design-patterns) |
| Elevator (car lifecycle) | 5 states (Idle, MovingUp, MovingDown, DoorOpen, Maintenance) | Full GoF State — one class per state, `Elevator` as context | [§7](15-elevator-system/README.md#7-design-patterns) |
| BookMyShow (booking status) | A handful of statuses (SEATS_LOCKED, PAYMENT_PENDING, CONFIRMED, ...) | **Rejected** full State pattern; table-driven `EnumMap<Status, EnumSet<Status>>` guard inside `Booking.transitionTo` | [§7](04-bookmyshow/README.md#7-design-patterns) |
| Slack Chat (message state) | 3-value enum (ACTIVE/EDITED/DELETED) | **Rejected** full State pattern; validation inside `Message`'s `synchronized` methods | [§7](16-slack-chat/README.md#7-design-patterns) |
| Snake and Ladder (game state) | 3 values (NOT_STARTED/IN_PROGRESS/FINISHED) | **Rejected** full State pattern; single enum + one guard clause | [§7](11-snake-and-ladder/README.md#7-design-patterns) |

**The deciding factor, stated consistently across all six README's "why/why
not" sections:** it is never "how many states are there" — it's **whether each
state has genuinely different *behavior* for the same operation, or merely
different *legality*.** Vending Machine, Food Delivery, and Elevator all have
real per-state behavioral divergence (e.g., `Elevator.tick()` does something
structurally different in `MovingUpState` vs. `DoorOpenState` — not just "is
this legal", but "what code runs"). BookMyShow, Slack, and Snake & Ladder only
need "is this transition legal" (a lookup table), with the actual mutation
being identical regardless of prior state — so the full class-per-state
machinery would be, in Snake and Ladder's README's words, *"three classes and
an interface to replace one `if` statement."*

**Illegal-transition prevention, compared:**
- **Vending Machine / Food Delivery / Elevator** (full State): illegality is a
  **compile-time absence** — a method simply doesn't exist on a given state
  class, or the state class's implementation of it throws immediately. The
  compiler/interface shape itself documents what's legal.
- **BookMyShow / Slack / Snake&Ladder** (table/guard): illegality is a
  **runtime check** against an explicit allow-list (`EnumMap`/`EnumSet` or an
  `if`), reviewed as a single method instead of scattered across N classes.

**When to reuse full State, when not to.** Reuse it when a future state is
likely to need materially different *logic* for an existing operation (the
Elevator README explicitly flags this: a future `EMERGENCY_STOP` state would
need `tick()` to behave completely differently, which full State accommodates
by adding one class with zero edits elsewhere). **Don't reuse it** — as
BookMyShow's README states explicitly — when the only per-state difference is
which transitions are legal; forcing full State there adds five classes to
express what one `EnumMap` already expresses, and makes the *transition table*
(the actual business rule reviewers care about) harder to see at a glance
because it's now smeared across five files instead of visible in one.

---

## 3. Observer / Pub-Sub: five applications, same pattern, different delivery guarantees

| Problem | Publisher → Subscriber | Delivery guarantee needed | README reference |
|---|---|---|---|
| Logging Framework | `Logger` → `Appender`s | Synchronous (in-process call) or async via `AsyncAppender`'s queue; at-least-once isn't the concern, ordering per-logger is | [§7](05-logging-framework/README.md#7-design-patterns) |
| Slack Chat | `ChatService` → `EventBus` → `NotificationService`, presence, mentions | Real-time, in-process fan-out; must not block the sender on slow subscribers | [§7](16-slack-chat/README.md#7-design-patterns) |
| Food Delivery | `Order` → `OrderTrackingObserver`s (customer/restaurant/rider apps) | Push *and* poll must both work — the polling path reconstructs state independent of the push path | [§7](08-food-delivery-system/README.md#7-design-patterns) |
| Stock Management | `InventoryService` → `LowStockAlertListener`, `ReorderAutomationListener` | Fire-after-commit, debounced (reorder listener tracks "already raised" to avoid repeat alerts) | [§7](13-stock-management-system/README.md#7-design-patterns) |
| Google Drive | `DriveService` → `NotificationService`/`FileChangeListener` | Minimal, deliberately not event-sourced — "the cleanest way to demonstrate an extension point," not a hard requirement | [§7](17-google-drive/README.md#7-design-patterns) |

**The common thread.** In all five, the publisher must *not* need to know the
concrete type or count of subscribers — this is always justified the same way:
without it, the publisher (`Logger`, `ChatService`, `Order`, `InventoryService`,
`DriveService`) would need to be edited every time a new consumer (a metrics
exporter, a second notification channel) is added, which is an Open/Closed
violation each README calls out by name.

**Where they differ, and why that's not a contradiction.** Logging's
`AsyncAppender` decouples via a `BlockingQueue` + one consumer thread *because
I/O latency must not block the caller*. Slack's `EventBus` is in-process and
synchronous-dispatch-with-async-option because chat delivery must *feel*
real-time. Stock Management's listeners run synchronously, right after the
mutation commits, specifically because alerting must never race ahead of the
mutation it's alerting about. Splitwise's README explicitly **rejects**
Observer for its ledger (an `ExpenseCreatedEvent` model), stating plainly that
async ledger updates would introduce eventual consistency with no benefit in a
single-process system — see
[Splitwise §7](09-splitwise/README.md#7-design-patterns). This is the clearest
"don't reuse" example in the whole repo: Observer is right for Slack (UI
responsiveness matters more than perfect ordering) and wrong for Splitwise's
ledger (correctness of a balance query matters more than decoupling).

**When to reuse, when not to.** Reuse Observer when the set of reactions to an
event is open-ended and growing, and when at least one reaction can tolerate
being slightly decoupled from the triggering call (logging, analytics,
notifications). **Don't reuse it** for any operation where the "observer" is
actually part of the correctness invariant itself (e.g., a balance ledger, a
seat-lock state machine) — those need the update to be part of the same atomic
operation, not a fan-out that could be slow, reordered, or dropped.

---

## 4. Locking/concurrency strategy, across all 17 problems

| # | Problem | Primary mechanism | Why this fits the access pattern | README reference |
|---|---|---|---|---|
| 01 | Parking Lot | Lock-free: `AtomicReference.compareAndSet` per spot + `ConcurrentSkipListSet`/`ConcurrentHashMap` for indexes | "Claim" *is* atomic index removal; no step ever needs two structures locked together | [§11](01-parking-lot/README.md#11-concurrency-and-thread-safety) |
| 02 | TTL Cache | Lock-free core (`ConcurrentHashMap`, `LongAdder`) + one narrow `ReentrantLock` per eviction-policy instance for the recency/frequency list structural update | Per-key map ops are independently atomic; only the *shared* eviction-order list needs a lock, and it's held for O(1)/O(log F) only | [§11](02-ttl-cache/README.md#11-concurrency-and-thread-safety) |
| 03 | Vending Machine | Coarse `ReentrantLock` on the front panel (`machineLock`) + separate fine-grained locks for admin ops (`synchronized` on `Slot`, a `ReentrantLock` in `CashBox`) | Physical hardware already serializes the front panel — no real parallelism to protect there; admin ops are genuinely concurrent with customer use and get their own locks | [§11](03-vending-machine/README.md#11-concurrency-and-thread-safety) |
| 04 | BookMyShow | Lock-free CAS: `AtomicReference<LockState>` per seat | Very short critical sections (microseconds), high contention only on popular seats — the textbook CAS-wins regime | [§11](04-bookmyshow/README.md#11-concurrency-and-thread-safety) |
| 05 | Logging Framework | Mixed: `CopyOnWriteArrayList`/`AtomicReference`/`volatile` for config, `ReentrantLock` for file-write critical section, `ArrayBlockingQueue` for async handoff | Reads (log calls) vastly outnumber config writes (CoW is ideal); file writes are genuinely serialized I/O that needs a real lock | [§11](05-logging-framework/README.md#11-concurrency-and-thread-safety) |
| 06 | LFU Cache | Pessimistic: single coarse `ReentrantLock` wrapping the whole `get`/`put` (via `ConcurrentLFUCache` decorator) | The core structure (frequency-bucketed doubly linked lists) requires multi-pointer updates with no natural CAS decomposition; correctness >> fine-grained throughput at this scope | [§11](06-lfu-cache/README.md#11-concurrency-and-thread-safety) |
| 07 | File Management System | Pessimistic, two-tier: one `ReentrantReadWriteLock` for tree structure + one lock per `File` for content | Structural ops (mv/rm/cp) need whole-subtree exclusivity; content writes to unrelated files must never contend with structure or each other | [§11](07-file-management-system/README.md#11-concurrency-and-thread-safety) |
| 08 | Food Delivery | Mixed: `AtomicReference`/`AtomicBoolean` CAS for independent single-field state (menu availability, partner status, payment status); `synchronized` on `Order` for causally-linked multi-field state | CAS where one field is the whole invariant; a lock where two fields (state + assigned partner) must move together | [§11](08-food-delivery-system/README.md#11-concurrency-and-thread-safety) |
| 09 | Splitwise | Pessimistic: one `ReentrantReadWriteLock` per `BalanceSheet`/group | A single expense is a genuine multi-key read-modify-write (payer + N participants) that `ConcurrentHashMap`'s per-key atomicity cannot express | [§11](09-splitwise/README.md#11-concurrency-and-thread-safety) |
| 10 | Meeting Scheduler | Pessimistic: per-resource `ReentrantLock`s, acquired in a fixed global sorted order with `tryLock(timeout)` | Multi-resource booking (1 room + N attendees) is exactly the deadlock-prone shape that requires strict lock ordering | [§11](10-meeting-scheduler/README.md#11-concurrency-and-thread-safety) |
| 11 | Snake and Ladder | None (by contract) — `volatile` only for safe publication | Documented single-writer-per-game contract; the one cross-thread read (`Player.position`) only needs visibility, not atomicity of mutation | [§11](11-snake-and-ladder/README.md#11-concurrency-and-thread-safety) |
| 12 | Contact Management | Optimistic: reference-identity CAS (`computeIfPresent` + `current == expected`) over immutable `Contact` snapshots | Low write contention expected (one user editing their own contacts); immutability makes "snapshot + swap" nearly free | [§11](12-contact-management-system/README.md#11-concurrency-and-thread-safety) |
| 13 | Stock Management | **All three**, deliberately, behind one interface: optimistic-versioned, pessimistic-locked, and CAS | The whole point of the problem is comparing the three; see §4a below | [§11](13-stock-management-system/README.md#11-concurrency-and-thread-safety) |
| 14 | Digital Wallet | Pessimistic: one `ReentrantLock` per `Account`, acquired in a **consistent sorted order** across the two accounts in a transfer | A debit is a multi-field check-then-act (balance *and* daily limit) that spans two lockable objects — CAS alone can't coordinate two objects | [§11](14-digital-wallet/README.md#11-concurrency-and-thread-safety) |
| 15 | Elevator System | Mixed: `ConcurrentSkipListSet` (lock-free) for stop queues, `AtomicInteger`/`AtomicLong` CAS for occupancy/counters, `volatile` + single-writer contract for car physical state | Each piece of state has a different writer-count profile; no single mechanism fits all of them | [§11](15-elevator-system/README.md#11-concurrency-and-thread-safety) |
| 16 | Slack Chat | Mixed: `AtomicLong` CAS for sequence counters/read-position, `synchronized` per-`Message` for compound edit/delete, `synchronized` per-`Bucket` for rate limiting | Deliberately per-key/per-message granularity throughout — explicitly avoids "synchronize everything," since editing message A must never block editing message B | [§11](16-slack-chat/README.md#11-concurrency-and-thread-safety) |
| 17 | Google Drive | Mixed: `ReadWriteLock` for tree structure, `synchronized` per-`File` for version history, CAS (`AtomicLong`) per-user for quota, `ConcurrentHashMap.compute()` for blob refcounts | Four different pieces of shared state, four different access patterns, four different (individually justified) mechanisms | [§11](17-google-drive/README.md#11-concurrency-and-thread-safety) |

### 4a. Optimistic vs. pessimistic vs. lock-free — the access-pattern rule of thumb this repo converges on

- **Lock-free CAS (`AtomicReference`/`AtomicLong`)** wins when the critical
  section is a **single field**, in-memory, and O(1) — Parking Lot (spot
  status), BookMyShow (seat lock state), Food Delivery (partner/payment
  status), Elevator (occupancy). The repo's own BookMyShow README states the
  boundary precisely: CAS stops being the right call the moment the critical
  section does meaningfully more work than an in-memory field swap (e.g. I/O).
- **Pessimistic locks (`synchronized`/`ReentrantLock`)** win when the critical
  section spans **multiple fields or multiple objects** that must move
  together — Digital Wallet (balance + daily limit, across two accounts),
  Splitwise (payer + N participants), Meeting Scheduler (1 room + N
  attendees), LFU Cache (multiple linked-list pointers with no clean CAS
  decomposition). Every one of these documents the **lock-ordering
  discipline** (sorted/consistent order) needed to avoid deadlock once more
  than one lock can be held at a time.
- **Optimistic versioned (CAS over a whole immutable snapshot)** wins when
  contention is expected to be **low** and you want CAS's non-blocking
  property *and* multi-field atomicity at once, by making the "single field"
  an entire immutable value object — Contact Management (`Contact` snapshots),
  and one of Stock Management's three ledger strategies
  (`OptimisticVersionedInventoryLedger`).
- **No synchronization (by documented contract)** wins only when a genuine
  single-writer invariant holds — Snake and Ladder is the one example, and its
  README is explicit that this is *not* "we forgot a lock," it's a documented,
  narrow contract (`volatile` for safe publication to a read-only observer,
  nothing more).

**When to reuse, when not to.** The repo's own evidence argues against ever
picking a locking strategy by analogy to "what the last problem did" — Stock
Management's side-by-side implementation of all three strategies over the
*same* domain operation is itself the strongest "don't pattern-match, analyze
the access pattern" artifact in the repo (see
[§11, strategies 1–3](13-stock-management-system/README.md#11-concurrency-and-thread-safety)).
A concrete misuse: applying BookMyShow's single-field CAS model to Digital
Wallet's transfer (two accounts, two fields each) would be a correctness bug —
CAS cannot atomically move money out of one account and into another while
also checking a daily limit; the Wallet README explains exactly why a lock is
required there instead.

---

## 5. Repository abstraction: common shape, domain-specific judgment calls

**What's common across every problem that uses it** (Parking Lot, TTL Cache
indirectly via `Cache<K,V>`, BookMyShow, Splitwise, Meeting Scheduler, Contact
Management, Stock Management, Digital Wallet, Slack Chat, Google Drive): a
narrow `XRepository` interface (`save`/`findById`/a couple of named queries)
with an `InMemoryXRepository` implementation, injected via constructor, so
that (a) the service layer is unit-testable without a database, and (b) a
future DB-backed implementation is a new class, not a rewrite. Every README
that uses it rejects the same alternative — "the service holds a
`Map`/`ConcurrentHashMap` directly" — for the same reason: it hard-wires
storage mechanism into business logic.

**What's domain-specific:**
- **Query surface size.** BookMyShow's repositories need exactly the few named
  queries the booking flow uses (`findByCityId`, `findByIdempotencyKey`) and
  explicitly reject a generic specification/query-by-example API as
  speculative (see [§7](04-bookmyshow/README.md#7-design-patterns)). Contact
  Management, by contrast, needs genuinely open-ended search (prefix,
  duplicate-detection) and layers a separate `NameIndex`/`IndexManager`
  *alongside* the repository rather than inside it — the repository stays
  narrow, and indexing is a separate Observer-driven concern (see
  [§7](12-contact-management-system/README.md#7-design-patterns)).
- **Atomicity requirements leaking into the interface.** Contact Management's
  `ContactRepository` exposes a `compareAndSet(id, expected, updated)` method
  — not a generic CRUD repository — specifically because the optimistic
  concurrency model (§4 above) needs that primitive at the repository layer,
  not layered on top of it. Stock Management's repositories are plain CRUD;
  all of its concurrency complexity lives in `InventoryLedger`, a separate
  abstraction, because inventory's concurrency story is about *quantities*,
  not about *which row exists*.
- **Cross-cutting consistency, not just storage.** Google Drive's
  `NodeRepository` is intentionally "dumb" (id → node) precisely because tree
  consistency (parent/child pointers) is guarded by `DriveService`'s
  structural lock, not by the repository — mixing those concerns would make
  the repository's contract ambiguous about what it guarantees.

**When to reuse, when not to.** Reuse the narrow `XRepository` + `InMemoryX`
shape whenever a service's persistence needs are genuinely just "find this
entity by its id or one other key." **Don't reuse it** as a dumping ground for
query logic that's actually a different concern (search/indexing, as Contact
Management shows) or for atomicity primitives that belong at a different layer
(as Stock Management's separate `InventoryLedger` shows) — folding everything
into "the repository" because that's what the last five problems did would
blur exactly the boundaries these READMEs are careful to keep separate.

---

## 6. Composite pattern: File Management System vs. Google Drive

| Aspect | File Management System | Google Drive |
|---|---|---|
| Component/Leaf/Composite | `FileSystemEntry` / `File` / `Directory` | `DriveNode` / `File` / `Folder` |
| Why Composite was needed | Uniform `size()`/tree-walking for `cp`/`mv`/`rm`/`ls` | Uniform permission checks, search, trash, move/rename |
| Concurrency model layered on top | Two-tier: `ReentrantReadWriteLock` (structure) + per-`File` lock (content) | `ReadWriteLock` (structure) + per-`File` intrinsic monitor (version history) + separate CAS (quota) |
| Extra concerns the tree carries | None beyond structure/content | Permission inheritance (ACLs walk the tree), content-hash dedup (blobs live *outside* the tree), per-user quota (orthogonal to tree shape) |
| README reference | [§7](07-file-management-system/README.md#7-design-patterns) | [§7](17-google-drive/README.md#7-design-patterns) |

**How they're similar.** Both reject the "single concrete class + `boolean
isDirectory` flag" alternative for the identical reason: it forces every
caller to branch on the flag, which is precisely the `if/else` duplication
Composite exists to remove, and it makes it legal (at compile time) to call
directory-only operations on a file.

**How they differ, concretely.** File Management System's tree *is* the whole
problem — content and structure are the only two pieces of state, and the
two-tier lock is sized exactly to that. Google Drive's tree is one of *four*
independent concerns layered together (structure, permissions, content
dedup/storage, quota), and its README is explicit that the tree's lock
protects *only* structure — version history has its own lock, blob refcounts
have their own atomicity mechanism, and quota has its own CAS counter,
entirely separate from the tree lock. Reusing File Management System's
"one coarse structural lock covers everything" mental model onto Google Drive
would be a correctness bug: a file's version history must remain readable
(via `File`'s own monitor) even while an unrelated part of the tree is being
restructured under the structural write lock, and quota must be checked and
reserved atomically *per user* independent of which part of the tree a write
touches.

**When to reuse, when not to.** Reuse Composite whenever a recursive
container/leaf structure needs to be traversed/measured uniformly. **Don't
reuse** a Composite-based tree's locking strategy wholesale onto a second
Composite tree that carries additional orthogonal concerns (permissions,
quota, versioning) — each concern needs its own concurrency story, as Google
Drive's README demonstrates by *not* copying File Management System's simpler
two-tier model unchanged.

---

## Summary: the one discipline every comparison above shares

Every pattern decision in this repo is made by asking **"what specifically
breaks without this pattern, here, in this problem"** — never "the last
similar-sounding problem used X, so I will too." The clearest proof of that
discipline is Stock Management's README implementing all three concurrency
strategies side by side specifically to make the trade-off empirical rather
than assumed, and BookMyShow's README keeping the *rejected* pessimistic-lock
alternative in its write-up (rather than silently dropping it) because it's
"a legitimate alternative with real trade-offs" — not a strawman. Use this
document as a map of *where* a given pattern is used well, not as a lookup
table to copy from.
