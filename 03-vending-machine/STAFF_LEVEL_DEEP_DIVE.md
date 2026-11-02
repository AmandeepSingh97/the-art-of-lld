# Staff-Level Deep Dive — Vending Machine

Twenty-four problem-specific, reasoning-based questions a Staff-level
interviewer would plausibly ask about *this exact design*, with full answers.
Organized by theme but numbered sequentially for easy reference.

---

### 1. Two customers can't physically use the same keypad at once — so why does `VendingMachine` need a lock at all? Isn't that over-engineering?

The lock isn't protecting against two *customers*; it's protecting against
the front panel and the admin/hardware channels touching the *same mutable
fields* (`balance`, `insertedDenominations`, `currentState`,
`selectedSlotId`) concurrently. Concretely: if a technician's remote
"force-enter-maintenance" command and a customer's `insertCoin` call raced
on `currentState` without a lock, you could get a torn transition — e.g. the
state visibly becomes `Maintenance` but `balance` still reflects an
in-progress cash insertion that no state machine code is defending anymore.
The lock is cheap (uncontended 99.9% of the time, since the scenario it
guards against is rare) and it eliminates an entire class of interleaving
bugs for the cost of one `ReentrantLock.lock()/unlock()` pair per call. The
real over-engineering mistake would be adding *per-field* locks or a
distributed lock manager for a problem this contained.

### 2. Walk me through what happens if the process crashes immediately after `CashPaymentStrategy` deposits cash into the `CashBox` but before `withdrawForChange` is called.

This is a real gap, and I'd rather name it than pretend it's handled. In the
current in-memory design, a crash here loses everything — the deposited cash
is gone from memory, the slot was never decremented, no transaction was
logged. On restart, the machine starts fresh with whatever cash float it was
last known to have *persisted* (which, today, is nothing, since there's no
persistence). The honest severity assessment: the customer lost their money
with nothing dispensed — a real support/compliance problem. The production
fix (§17 in the README) is to make the deposit step itself durable and
idempotent — e.g. write "pending settlement: inserted {$1, $0.25, $0.25},
target slot A1" to a local durable log *before* attempting the change
withdrawal, so a restart can detect an interrupted settlement and either
complete it (retry the withdrawal) or refund it (if the hardware state shows
no dispense occurred). The current implementation deliberately keeps this
step in-memory only because durability was out of the stated interview
scope — but I'd flag this as the first thing I'd add given more time.

### 3. Suppose a new requirement arrives: "support a loyalty program where a registered customer's purchase is free every 10th time, tracked per card." Which of your classes change, and which don't?

`PaymentStrategy` is the right seam: I'd add a new implementation,
`LoyaltyPaymentStrategy`, that wraps `CardPaymentStrategy` (decorator-style)
and checks a loyalty counter before delegating to the real charge. Neither
`VendingMachineState` nor `VendingMachine` needs to change — `IdleState.payByCard`
already just calls `PaymentStrategy.settle(...)` against whatever strategy is
configured; it has no knowledge of *which* strategy. What *would* need to
change: `VendingMachineContext` would need a way to identify "which customer"
(today there's no customer identity at all — purchases are anonymous), which
is a bigger structural addition — a `CustomerIdentity` concept threaded
through `payByCard`. That's the honest answer: the payment mechanism extends
cleanly (Open/Closed holds), but "who is the customer" is a dimension the
current domain model doesn't have at all, and bolting it on is a real schema
change, not a free extension.

### 4. Your `OutOfStockState` is global — all slots empty. What if the real requirement is "stop selling a *specific* product line machine-wide for a recall," not "all slots happen to be empty"?

That's a different concept from `OutOfStockState` and I would not overload
it. "All slots empty" is a *computed, incidental* machine-wide condition.
"This product is recalled" is an *administrative directive* that should
disable specific slots regardless of their quantity. I'd add a `disabled`
boolean (or a `SlotStatus` enum: `ACTIVE`, `DISABLED_FOR_RECALL`) directly on
`Slot`, checked alongside `isEmpty()` in `selectProductForCash`/`payByCard`
(`if (slot.isEmpty() || slot.isDisabled()) throw ...`). This is a
slot-level concern, not a machine-state concern, which is exactly why I
separated "per-slot stock-out" (an exception, no state transition) from
"whole-machine stock-out" (a state) in the original design — the same
separation extends naturally to "per-slot disablement" without touching the
state machine at all.

### 5. If this machine had 10 million transactions a day, what's the first thing that falls over?

Nothing in `VendingMachine` itself realistically could — a single physical
vending machine cannot physically transact 10 million times a day (that's
~115 transactions/second, continuously, forever, through one coin slot).
That volume implies the question has silently become "a fleet of machines
reporting to a shared backend," which is explicitly out of scope for this
design (§1, §11, §14 in the README all name this boundary directly). If
forced to answer for *this* single-process object anyway: `TransactionLog`'s
`CopyOnWriteArrayList` would be the first thing to hurt, because every write
copies the entire backing array — at high append rates this is O(n) per
write and becomes quadratic overall. The fix for a genuinely high-write
single-process log would be a bounded ring buffer or a different concurrent
structure (e.g. `ConcurrentLinkedQueue` with periodic draining to storage),
not a redesign of the purchase flow.

### 6. You chose greedy change-making and justified it with "the US denomination set is canonical." What's your actual proof obligation there, and would you trust that claim without checking it in an interview?

The honest bar: I know this is a well-established, provable result for *this
specific* denomination family, but I would not derive the general canonical-
coin-system theorem from scratch live in an interview — I'd state the claim,
give the standard counter-example for *why it's not universally true*
(`{1,3,4}` for amount 6), and make clear my confidence is "this is a known
result for the standard denominations, not something I'm deriving on the
spot." What I *can* defend rigorously without appealing to authority: for
any amount representable within the available counts, greedy here never
gets stuck needing to "break" a larger coin it already spent, because every
denomination in `{1,5,10,25,100,500,1000,2000}` divides evenly into a clean
relationship with its neighbors in practice (25 = 5×5, 100 = 4×25, etc. —
informally, no "gap" exists that greedy could fall into). The important
engineering point, which I *can* fully defend, is that the `ChangeMaker`
interface makes this an isolated, swappable assumption — if I'm wrong about
canonicality for some exotic configured denomination set, the blast radius
is one class, not the whole payment path.

### 7. Why does `Slot` hold its own lock instead of `Inventory` holding one lock for all slots?

Granularity. If `Inventory` held a single lock for all slot mutations, a
customer dispensing from slot A1 would block an admin restocking slot Z9,
even though they share no state. That's unnecessary contention for a
correctness property (no lost updates) that only requires protecting *the
specific slot being touched*. The cost of per-slot locks is one monitor per
`Slot` object — negligible — and the benefit is that unrelated slots never
contend. This is the standard "lock the smallest thing that needs it"
principle, and it's directly verifiable: nothing in `CashBox` or
`Inventory`'s map operations depends on a specific `Slot`'s internal count,
so there's no correctness reason to couple their locks.

### 8. What's the actual consistency guarantee between `CashBox`'s counts and `Slot`'s quantity after a purchase — could they ever disagree?

They are updated via two independent locks (`CashBox`'s internal lock,
`Slot`'s monitor) in sequence within `HasMoneyState.selectProductForCash`:
payment settles (mutates `CashBox`) strictly before `slot.dispenseOne()`
mutates the slot. There is no atomic cross-lock transaction tying them
together — if the process crashed *between* those two calls, `CashBox` would
reflect a completed sale that `Slot` does not. This is the same gap named in
Q2, restated from the inventory side: the design provides correctness
*within* each resource under concurrent access, but does not provide
crash-atomicity *across* resources. Closing that gap for real would need
either a single transactional boundary (e.g. both mutations recorded in one
durable write before either is "visible") or a reconciliation process that
can detect and repair the disagreement after a crash (compare "cash
collected this period" against "units dispensed this period" and alert on
mismatch).

### 9. Why is `MaintenanceState` only enterable from `Idle`/`OutOfStock` — what if there's a hardware emergency (coin jam) that needs to interrupt a customer mid-transaction?

That's a legitimate real-world need I deliberately did not support, and I'd
say so rather than claim coverage I don't have. The current design
intentionally makes "abandon an in-flight, possibly-already-paid transaction"
*impossible* through the normal API, because silently abandoning a paid
transaction is a money-safety bug, not a feature. A real emergency-stop would
need to be a distinct, explicitly-named operation — e.g.
`VendingMachine.emergencyStop()` — that is allowed from *any* state, but
whose contract is explicit about what happens to in-flight money: it should
force a refund of whatever's in `insertedDenominations` (if in `HasMoneyState`)
before transitioning to `Maintenance`, not just abandon it silently. I didn't
build this because it wasn't in the stated scope, but it's the correct
next addition if "hardware emergency" is a real requirement, and it's a
good illustration of why I don't want operator overrides to bypass the
state machine quietly — they need their own explicit, audited path.

### 10. Your `Product.price` uses `volatile` instead of a lock. Justify that specifically — what could go wrong, and why doesn't it?

`volatile` guarantees two things here: writes are immediately visible to
other threads (no stale-read risk), and the write itself is atomic for a
single reference assignment (no risk of a half-written object, since
`Money` is immutable and swapped wholesale, not mutated in place). What
could go wrong with a *lock* instead: nothing extra would be gained, because
there's no multi-step invariant involving `price` that another thread could
observe halfway through — a reprice is a single, independent write. Where
`volatile` would *not* be enough: if repricing needed to be atomic with
something else (e.g. "reprice AND reset the slot's recent-sales counter
together, with no reader ever seeing one updated and not the other"), you'd
need a lock or an immutable composite object swapped atomically instead.
I chose `volatile` because I checked that no such combined invariant exists
for `price` today — not as a default, reflexive choice.

### 11. If I asked you to ship a usable version of this in two weeks, what would you cut, and what would you refuse to cut?

Cut: the `MaintenanceState`/admin auth story (ship restock as an
unauthenticated internal tool), the DP change-making fallback (ship greedy
only, documented assumption), persistence (accept the crash-loses-state gap
as a known limitation, written down), and `TransactionLog` durability (keep
it in-memory, export periodically via a cron-style dump if anyone needs
history). Refuse to cut: the State pattern's core transitions (Idle →
HasMoney → Dispensing → Idle/OutOfStock), the pay-before-dispense ordering
invariant, and the insufficient-change refund path — those three are the
difference between "a vending machine" and "a machine that sometimes keeps
your money," and that's not an acceptable trade for a two-week deadline.

### 12. Why does `CashPaymentStrategy` deposit the customer's cash into the box *before* confirming change can be made, instead of checking feasibility first and only depositing on success?

Good challenge — on the surface, "check first, then commit" sounds safer
and avoids the deposit/reverse dance entirely. The reason I didn't do that:
the change-making check needs to know what's *already* in the box, which
includes the customer's just-inserted cash in a realistic scenario — e.g. a
customer inserts a single $20 note for a $1.25 item; the box may have
plenty of quarters already, but if it's marginal, the $20 itself isn't
useful for making the $18.75 change (a $20 can't be broken into smaller
bills by the box), so whether you deposit it first or not doesn't change
feasibility in that case. But in other cases order *could* matter if the
inserted denominations themselves are small enough to be part of the change
(unlikely in practice — customers don't overpay with exact-change-sized
coins expecting change back from them — but not provably impossible). I
chose "deposit, then attempt, then reverse-on-failure" because it's the
simpler invariant to state and verify ("the box's counts always reflect
'customer cash currently held', symmetric for both deposit and refund"),
at the cost of needing an explicit reversal path, which I built and tested
(`CashBoxTest`, `VendingMachineTest#insufficientChangeAbortsAndRefundsExactInsertedCash`).
A "check first" alternative would avoid the reversal code but would need to
simulate the post-deposit state without mutating it first — more complex
for a correctness property that's actually easier to get right the way I
did it.

### 13. Two admins issue a restock and a reprice on the same slot concurrently — what's your actual guarantee?

`Slot.restock` is `synchronized` on the slot and only touches `quantity`;
`reprice` goes through `Product.repriceTo`, which is `volatile` and touches
a *different* object (`Product`, not `Slot`) entirely. Since they mutate
disjoint state with no shared invariant between "how many units" and "what
price," there's no race to worry about between those two specific
operations — they can run fully concurrently and the outcome is
deterministic regardless of interleaving (whichever reprice call runs last
wins, independent of restock). If a *combined* admin operation existed —
"replace this slot's product and reset its quantity atomically" — that's
exactly why `Slot.reassignProduct` exists as a single `synchronized` method
rather than two separate calls: I already anticipated that specific compound
invariant and gave it one atomic method rather than exposing "set product"
and "set quantity" as two calls an admin tool could interleave incorrectly.

### 14. What's the actual difference, in your design, between `InsufficientFundsException` being "recoverable" and `InsufficientChangeException` being "not recoverable" — couldn't you also just let the customer insert more money to help make the change?

Asking the customer to insert *more* money to help make change for the
*existing* balance is backwards — it would increase, not decrease, the
change owed (since balance grows but price doesn't). The only way more
money insertion helps make-change-feasibility is if the *new* amount
inserted happens to be exactly-sized small denominations the box is short
on (e.g. the box has no dimes, the customer keeps inserting quarters, and
eventually the total overshoot forms change payable purely in quarters).
That's a real, theoretically valid recovery path I did not implement —
today, any `InsufficientChangeException` immediately aborts and refunds. I'd
defend that as the right default (simpler, and "please try inserting a
specific extra set of coins to unstick my own change-making algorithm" is a
confusing UX for a real customer), but I'd name the alternative explicitly
if asked for "the most customer-friendly version," rather than claim my
current behavior is strictly optimal.

### 15. Why is `AdminService` a separate class instead of just more methods on `VendingMachine`?

Interface Segregation, concretely: a keypad/touchscreen UI only ever needs
`insertCoin`/`selectProductForCash`/`payByCard`/`cancel`. If `restock`/
`reprice`/`collectCash`/`enterMaintenance` lived directly on
`VendingMachine`, every caller — including the untrusted customer-facing
UI — would compile against (and could theoretically be tricked into
calling) operator-only operations. Splitting them into `AdminService` means
the customer-facing UI's dependency type (`VendingMachine`) *cannot even
reference* admin operations, which is a stronger guarantee than "we just
don't expose a button for it in the UI" — it's enforced at the type level,
one compile error away from being caught if someone tries to wire the wrong
thing up.

### 16. Your `VendingMachine.getCurrentStateName()` reads `currentState` without the lock. Under what conditions could that actually mislead a caller, and is that acceptable?

It could return a state that's already stale by the time the caller acts on
it — e.g. a UI reads "IDLE" and a fraction of a second later (before the UI
renders), a concurrent admin restock-triggered transition or a different
in-flight customer operation (there shouldn't be one, by definition of
"IDLE", but a racing admin `enterMaintenance` could) changes it to
`MAINTENANCE`. This is acceptable specifically *because* this method is
documented as display-only — nothing in the purchase flow *decides* behavior
based on this method's return value; every real decision goes through
`currentState.someOperation(...)` dispatch, which *is* lock-protected. If
this method's result were ever used to gate a business decision (e.g. "only
call `insertCoin` if `getCurrentStateName()` says IDLE"), that would
reintroduce a check-then-act race — and that's exactly why the public API
doesn't ask callers to pre-check state before acting; it lets the state
object itself reject invalid operations atomically.

### 17. Suppose the requirement changes to "a machine can hold two of the same product in different slots, and should dispense from whichever has the most stock, for even wear." What changes?

`Inventory` currently only supports direct slot-id addressing
(`getSlot(slotId)`) because the assumed customer interaction is "press A1,"
not "ask for Coke." Supporting wear-leveling requires `Inventory` to also
expose a product-id → candidate-slots index and a selection policy (most
stock first, or round-robin). This is a genuinely new capability, not a
refactor of existing code: I'd add `Inventory.findBestSlotForProduct(productId)`
using a `Comparator` over slots filtered by product id and non-empty, and
change the customer-facing selection API to optionally take a product id
instead of (or alongside) a slot id. `Slot`, `CashBox`, `PaymentStrategy`,
and the whole state machine are untouched — this is a change entirely
contained in `Inventory` and the very top of the selection call path, which
is a reasonable sign the original boundary between "inventory lookup" and
"payment/dispense logic" was drawn in the right place.

### 18. How would you add support for "exact-cash-only mode" (no change given, machine rejects overpayment) without duplicating `HasMoneyState`'s logic?

I would not fork `HasMoneyState`. Instead, I'd parameterize `CashPaymentStrategy`
(or add an `ExactCashOnlyPaymentStrategy` alternative implementing the same
`PaymentStrategy` interface) that rejects — rather than attempts to make
change for — any `changeOwed > 0`, immediately throwing a dedicated
`OverpaymentNotAllowedException` and triggering the same refund-and-abort
path `InsufficientChangeException` already uses. Because `HasMoneyState`
only knows about `PaymentStrategy` as an interface, swapping which concrete
strategy is wired into the machine at construction time handles this with
zero changes to the state classes — this is the same Open/Closed argument
as Q3 and Q18, which collectively is good evidence the Strategy boundary
was placed correctly rather than just asserted to be "flexible."

### 19. Your tests cover a lot of happy paths and some failures — what's the one test you'd add first if I told you a bug report came in about double-dispensing?

A test that specifically races *the payment settlement and the dispense*
together across multiple threads on a slot with exactly 2 units and asserts
that the sum of (successful dispenses) never exceeds the sum of (successful
payment settlements) — i.e., verifying the pay-before-dispense *ordering*
holds under concurrency, not just that quantities don't go negative (which
`concurrentCardPurchaseAttemptsOnLastUnitDispenseExactlyOnce` already
covers for a single-unit case). I'd extend that existing test to a
multi-unit slot and assert the transaction log's count of `CARD:...`
entries equals exactly the number of successful `payByCard` calls — not
more, not fewer — which would catch a bug where `dispenseAndLog` fired
without a corresponding successful `settle`, something a pure
quantity-never-negative assertion wouldn't catch.

### 20. What's your actual backward-compatibility story if `PaymentStrategy.settle`'s signature needs to change — say, to support partial payments?

Because `PaymentStrategy` is an interface with exactly two implementations,
both owned inside this same module, a signature change is a single
coordinated edit — there's no external/third-party implementer to break.
If this were a public SDK interface (e.g. third parties plug in their own
payment strategies), I would not change the method signature directly;
I'd either add a new default method with the new capability
(`default PaymentOutcome settlePartial(...) { return settle(...); }` as a
fallback) or introduce a new interface (`PartialPaymentStrategy extends
PaymentStrategy`) that only strategies supporting the new capability
implement, checked with `instanceof` at the call site. The general
principle: breaking changes are fine when you own every implementer;
additive, opt-in extension is the right move the moment you don't.

### 21. If a single machine's `CashBox` ran completely dry on small denominations permanently (never gets restocked with coins, only bills get deposited), what happens over time, and is that a bug?

Not a bug — it's the expected, correct consequence of the `InsufficientChangeException`
path: every cash purchase requiring non-trivial change will fail and
auto-refund until an admin loads more small denominations via
`AdminService.loadCashFloat`. This is arguably a *feature* (the machine
never silently shortchanges a customer or keeps money it can't make change
for) but it does mean the machine effectively "fails closed" into
cash-purchase-unavailability rather than continuing to transact
incorrectly. The operational gap is monitoring: nothing in this design
alerts an operator that the float is trending toward this state *before* it
happens — that's exactly the `InsufficientChangeException` rate metric
named in README §17, which exists precisely to catch this before customers
start getting refused.

### 22. Why didn't you make `VendingMachineState` a `sealed interface` with Java 17's pattern matching, given the state set is fixed and known?

I considered it and deliberately didn't, for one concrete reason: `sealed`
would require every permitted implementation to be declared up front
(`permits IdleState, HasMoneyState, ...`), which is fine for *this*
interview-scope solution, but it works against the Open/Closed extensibility
story I'm making elsewhere in the design (e.g. Q18's "swap in a new payment
strategy with zero changes to existing code" — the same philosophy should
apply to states). If a real deployment needed a new state (say, a
`CardReaderOfflineState` that only blocks card operations but still allows
cash), sealing the interface would force editing the `permits` clause on the
interface itself, coupling every new state to a change in a shared file —
exactly the kind of coupling I avoided elsewhere. I'd reconsider `sealed`
only if the interviewer confirmed the state set truly will never grow,
which is a product decision I'm not in a position to assume.

### 23. Walk me through exactly what makes your deadlock-freedom claim true, formally — not just "I didn't see a problem."

The claim rests on a strict partial order over the locks actually used:
`machineLock` (in `VendingMachine`), `Slot`'s intrinsic monitor, and
`CashBox`'s internal `ReentrantLock`. Every code path that acquires more
than one of these acquires `machineLock` first, and *never* acquires
`machineLock` while already holding a `Slot` or `CashBox` lock — because
`AdminService`'s direct calls into `Slot.restock`/`CashBox.load`/
`collectExcept` never touch `machineLock` at all (they bypass the state
machine, by design, as discussed in README §11), and every `VendingMachine`
public method acquires `machineLock` *before* dispatching into state logic
that might then touch `Slot`/`CashBox`. A cycle would require some code path
to acquire `Slot`/`CashBox` first and then block waiting for `machineLock` —
I verified by inspection that no such path exists in the current code (it
would require `AdminService` or a `Slot`/`CashBox` method to call back into
`VendingMachine`'s public API, which neither does). This is the standard
"total/partial lock ordering" sufficient condition for deadlock-freedom; it
is a real proof obligation I can point to specific code for, not a hope.

### 24. If you had to simplify this whole design down to the absolute minimum that still "counts" as a vending machine for a 2-week internal demo, what's the smallest version, and what do you explicitly tell stakeholders it does NOT handle?

Smallest version: `Product`, `Slot`, `Inventory`, `Money`, one `VendingMachine`
class with a single `enum MachineState` (no State pattern classes — a plain
switch is acceptable at this size), cash-only (no card, no `PaymentStrategy`
interface), exact-change-only (no `ChangeMaker`/`CashBox` — refuse purchases
unless inserted amount exactly equals price), and no admin service (restock
via a direct setter, no auth). I would tell stakeholders explicitly, in
writing, that this demo version: does not give change, does not accept
cards, has no concurrency protection beyond "don't run two threads against
it," and has no persistence — i.e., it demonstrates the *happy-path shape*
of the problem, not a production-viable machine. The full design in this
repo is what I'd propose building next, specifically because every piece
I'd add back (State pattern, Strategy, locking, refund-on-failure) maps
directly to a named gap in the 2-week version, not to speculative
future-proofing.
