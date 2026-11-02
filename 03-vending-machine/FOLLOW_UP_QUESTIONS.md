# Follow-Up Questions — Vending Machine

25+ realistic interviewer follow-ups, categorized, each with a full answer.
No question is repeated across categories even where topics brush against
each other.

---

## Object-Oriented Design

**1. Why is `Product` mutable (price can change) while `Money` is immutable?**
Different lifetimes and different invariant needs. `Product` represents a
catalog entry whose price is expected to change over its lifetime (an admin
reprices it) while remaining "the same product" (same id, same identity) —
that's a classic case for a mutable entity with stable identity
(`equals`/`hashCode` on `productId` only). `Money`, by contrast, is a pure
value — two `$1.25`s are completely interchangeable, there's no "identity"
to preserve, and making it immutable means it can be freely shared across
threads (passed into `CashPaymentStrategy`, stored in a `Transaction`)
without any copying or defensive-copy discipline.

**2. Why does `Slot` hold a `Product` reference rather than embedding the product's fields (name, price) directly?**
Because the same product can occupy more than one slot (two coils of Coke),
and if a `Slot` embedded copies of name/price, repricing would require
updating every slot that happens to hold that product — an update-anomaly
bug waiting to happen. Holding a reference means one `repriceTo` call on the
shared `Product` instance is instantly reflected everywhere it's stocked.

**3. Is `Inventory` a repository or a domain service — which, and why does it matter?**
It's closer to a repository (collection-oriction abstraction over `Slot`
storage with lookup-by-id) that also happens to expose one small piece of
domain logic (`isCompletelyEmpty()`). The distinction matters for where you
put business rules: if `Inventory` were a "pure" repository, `isCompletelyEmpty`
would arguably belong in a separate domain service that *queries* the
repository. I kept it on `Inventory` because it's a trivial, purely-derived
predicate with no other dependencies — promoting it to a separate service
class would be ceremony without payoff at this size.

**4. Why does `VendingMachineContext` exist as a separate interface instead of just passing the `VendingMachine` instance directly to state classes?**
Interface Segregation: state classes need a specific, small set of
operations (get/set balance, transition state, dispense) — they should not
be able to call, say, a hypothetical future `VendingMachine.shutdown()` or
`adminOnlyMethod()`. A narrower interface also makes state classes trivially
unit-testable against a hand-rolled fake `VendingMachineContext`, without
constructing a full `VendingMachine` with real `Inventory`/`CashBox`
dependencies.

**5. `AdminService` takes a `VendingMachine` in its constructor rather than extending it — walk me through why composition was chosen over inheritance here.**
"An admin service IS-A vending machine" is false — an `AdminService` doesn't
have a balance, can't be inserted coins into, and isn't something a state
machine transitions. It *uses* a machine to perform operator actions on it.
Composition correctly models "has access to" without implying
substitutability (you'd never want to pass an `AdminService` somewhere a
`VendingMachine` is expected, which inheritance would wrongly permit).

---

## SOLID Principles

**6. Where exactly does this design apply the Single Responsibility Principle, concretely (not just "each class does one thing")?**
`CashBox` only manages the physical cash float's arithmetic and locking —
it has zero knowledge of products, prices, or what a "purchase" is.
`ChangeMaker` only computes a denomination breakdown — it never touches the
box's actual counts (that's `CashBox.withdrawForChange`'s job, which calls
the `ChangeMaker` but owns the mutation). `VendingMachineState` subclasses
only decide "is this operation legal right now, and what's the next state"
— they delegate the actual payment math to `PaymentStrategy`. Each of these
could change for an independent reason (new denomination set, new change
algorithm, new state transition rule) without forcing a change in the
others — that's the actual test for SRP, not just "the class is short."

**6b. Does `VendingMachine` itself violate SRP by owning the lock, the context implementation, AND being the public facade?**
It's a defensible, bounded combination rather than a violation, because all
three responsibilities serve one cohesive purpose: being the single
coordination point for one physical machine's front panel. If `VendingMachine`
also contained payment math, change-making logic, or admin-auth logic,
*that* would be an SRP violation — and it doesn't; all of that is delegated
out. "One class can have one carefully-scoped responsibility with several
small facets" is different from "one class does unrelated things."

**7. Which part of this design is the clearest Open/Closed Principle example, and what would break it?**
`ChangeMaker` and `PaymentStrategy` are the clearest examples — adding a new
change algorithm or payment method requires zero modification to
`CashPaymentStrategy`, `CardPaymentStrategy`, or any state class, only a new
implementation plus a different constructor argument to `VendingMachine`.
What would break OCP: if `HasMoneyState.selectProductForCash` contained an
`if (changeMaker instanceof GreedyChangeMaker)` branch anywhere — it
doesn't, and that's the thing to verify, not just assert.

**8. Liskov Substitution: can every `VendingMachineState` implementation be substituted for `VendingMachineState` without breaking callers?**
Yes, with one deliberate nuance: every concrete state can throw
`InvalidStateTransitionException` for operations it doesn't support, and
every caller (`VendingMachine`'s public methods) is written to expect and
propagate that exception rather than assuming all operations always
succeed. This is LSP-compliant because the *contract* of
`VendingMachineState` (as documented on the interface) explicitly allows
throwing for unsupported operations — it's not a surprising, undocumented
behavior change per implementation; it's part of the declared contract
every implementation honors identically.

**9. Where would adding "support a new payment method" violate Interface Segregation if you weren't careful, and how did you avoid it?**
If `PaymentStrategy` had a single bloated method like
`settle(Money price, VendingMachineContext ctx, boolean isCash, boolean isCard, String cardToken, List<Denomination> insertedCoins)`,
every implementation would be forced to deal with parameters irrelevant to
it (a card strategy ignoring coin params, a cash strategy ignoring card
params). I avoided it by giving `PaymentStrategy` exactly one method with a
narrow signature (`settle(Money, VendingMachineContext)`) and letting each
implementation pull only what it actually needs from the `VendingMachineContext`
(cash pulls `getInsertedDenominations()`/`getCashBox()`; card pulls only
`getCardGateway()`).

**10. Dependency Inversion: name the one dependency injection point that matters most here and why.**
`VendingMachine`'s constructor taking `ChangeMaker` and `CardPaymentGateway`
as interface types (not concrete classes) is the most load-bearing one —
it's what makes `MockCardPaymentGateway` substitutable for tests without
touching production wiring, and what makes the DP change-making fallback
(discussed in README §10) a non-invasive swap. Without this inversion,
`VendingMachine` would `new GreedyChangeMaker()` and `new RealCardGateway()`
internally, coupling it to concrete implementations and making it untestable
without hitting a real (or real-shaped) payment network.

---

## Design Patterns

**11. Could you implement this whole thing with just an `enum` for state and no State-pattern classes at all? What would you lose?**
Yes, mechanically — `enum MachineState { IDLE, HAS_MONEY, ... }` plus a
`switch` in every `VendingMachine` method. You'd lose: (a) compiler-enforced
co-location of "what's legal in this state" (today, forgetting to handle a
state in a new operation is silently a default-throw; with a hand-written
switch, forgetting a `case` is either a compile warning you might ignore or,
worse, a silent fallthrough bug), and (b) the ability to unit-test one
state's logic (`HasMoneyState.selectProductForCash`) in isolation against a
fake context, without constructing a whole `VendingMachine`.

**12. Is `PaymentOutcome` a Value Object or a DTO, and does the distinction matter here?**
It's closer to a Value Object (immutable, equality would be structural if
defined, represents a complete "result" concept) than a DTO (which implies a
serialization/transport boundary that doesn't exist here — everything's
in-process). The distinction matters mainly for whether you'd add
`equals`/`hashCode`/serialization annotations — I didn't, because nothing in
this codebase compares two `PaymentOutcome`s for equality or sends one over
a wire; adding that machinery now would be speculative.

**13. Why not use the Chain of Responsibility pattern for validation (checking funds, then stock, then change-ability) instead of sequential method calls?**
Chain of Responsibility shines when the *set and order* of validations needs
to be dynamically configurable or extended by unrelated modules. Here, the
validation sequence (slot exists → slot not empty → funds sufficient →
change makeable) is small, fixed, and tightly coupled to the exact
exception each failure should raise — expressing it as a chain of generic
"handler" objects would add indirection without adding real flexibility,
since no requirement calls for reordering or dynamically injecting new
validation steps.

**14. You rejected Singleton for `VendingMachine` — but isn't "there's only one cash box" a legitimate case for Singleton?**
No — "there's conceptually one cash box per machine instance" is an
*instance-scoping* fact, not a *process-wide uniqueness* fact. Singleton
would force exactly one `CashBox` to exist in the entire JVM, which breaks
the moment you want two independent `VendingMachine` instances in the same
test process (as the test suite does, extensively) or in the same
multi-machine management console. Instance-per-`VendingMachine`, injected
via constructor, gives you "one cash box per machine" correctly without the
JVM-wide constraint Singleton would wrongly impose.

**15. Where in this design would the Observer pattern actually earn its keep if a new requirement appeared, and what's the trigger that would justify adding it?**
The trigger would be a UI needing to *react* to state transitions
asynchronously — e.g. a touchscreen that must repaint the moment the
machine enters `DispensingState` without polling. At that point,
`VendingMachineContext.transitionTo` would be the natural place to notify a
list of registered `StateChangeListener`s. I didn't add this speculatively
because nothing in the current scope needs push notifications — the Demo
class and tests both just call methods and read return values/state
directly, which is simpler and sufficient today.

---

## Data Structures

**16. Why `EnumMap` for `CashBox.counts` instead of a plain `int[]` indexed by `Denomination.ordinal()`?**
`int[]` would be marginally faster and more compact, but it throws away type
safety (any `int` index would compile, including an out-of-bounds one caught
only at runtime) and readability (`counts[3]` vs `counts.get(Denomination.ONE_DOLLAR)`).
`EnumMap` is specifically designed to give array-like performance (it's
backed by an array internally) while keeping the enum-keyed API — it's the
"best of both" choice for a small, fixed enum key set, which is exactly
this situation.

**17. Your `GreedyChangeMaker` is O(d) where d ≤ 8 — is that actually a meaningful complexity claim, or is it trivial?**
It's a real, worth-stating claim specifically *because* it's bounded and
small — the point of stating it is to contrast it with the DP alternative's
`O(amount × d)`, which is asymptotically worse for large amounts despite
being more "generally correct." Big-O is still meaningful even at small
constant bounds; stating it explicitly is what lets you make an informed
trade-off decision (§10/§16 in README) instead of picking an algorithm by
gut feel.

**18. Why `List<Denomination>` for `insertedDenominations` instead of a `Map<Denomination, Integer>` like the cash box uses?**
Because refund needs the *insertion order and exact multiset*, not just
counts, to faithfully replay "give back what was put in" — a `Map` would
lose nothing numerically (you could reconstruct counts), but a `List` is
the more direct, obviously-correct representation for "this is literally
the sequence of coins/bills the customer fed in," and it's small (a handful
of entries per transaction) so the lack of O(1) count lookups is irrelevant.

**19. Could `Inventory.isCompletelyEmpty()`'s O(n) slot scan become a problem, and how would you avoid it if it did?**
Only at unrealistic slot counts (thousands+) for a single physical machine.
If it somehow mattered, I'd maintain a running `nonEmptySlotCount` counter,
incremented/decremented at the same synchronization points as `Slot.dispenseOne`/
`restock` (likely via a callback or by moving the counter into `Inventory`
itself with its own lock) — turning the check into O(1) at the cost of one
more piece of state to keep consistent. I didn't do this preemptively
because the O(n) scan is already fast enough for any realistic slot count,
and the extra counter would be exactly the kind of premature optimization
the engineering standards warn against.

---

## Java Internals

**20. `Money` is a `record` — what does that buy you for free, and what did you still have to write by hand?**
Free: a canonical constructor parameter list, `equals`/`hashCode` based on
all components, a default `toString`, and accessor methods named after the
fields (`cents()`). Written by hand: the *compact constructor* validation
(rejecting negative values), all the arithmetic methods (`add`, `subtract`,
comparison helpers), and an overridden `toString` (because the generated one
would print `Money[cents=125]`, not `$1.25`) — records give you the
boilerplate-elimination for data-carrying, not domain behavior.

**21. Why does `Money.ofDollars` use `Math.round(dollars * 100)` instead of just casting to `long`?**
A plain cast truncates toward zero, and because `double` cannot exactly
represent most decimal fractions (e.g. `1.1 * 100` might evaluate to
`109.99999999999999` due to binary floating-point representation), a
truncating cast could silently produce `109` cents instead of `110`.
`Math.round` rounds to the nearest `long`, which correctly recovers the
intended value despite the floating-point noise — this is the standard,
necessary mitigation whenever you must convert a `double` dollar amount into
exact integer cents.

**22. What guarantee does `ReentrantLock` give you that `synchronized` doesn't, and where did you actually need it?**
`ReentrantLock` supports `tryLock`, timed acquisition, and interruptible
acquisition — none of which this design currently uses, so honestly, for
`machineLock` and `CashBox`'s internal lock, plain `synchronized` would work
identically today. I used `ReentrantLock` for those two specifically because
they're the two places a production evolution (named in README §17) is most
likely to want a `tryLock`-based timeout (e.g. "if the front panel lock can't
be acquired within 2 seconds, something's stuck — fail safe rather than hang
the hardware") — using `ReentrantLock` now means that extension doesn't
require changing the locking primitive later, just adding a parameter to an
existing call.

---

## Multithreading

**23. Explain precisely why `Slot.dispenseOne` being `synchronized` prevents the specific race of "two threads both see quantity=1 and both decide it's safe to dispense."**
`synchronized` on an instance method acquires the object's intrinsic
monitor for the full method body. Both the *read* of `quantity` (the
`if (quantity == 0)` check) and the *write* (`quantity--`) happen while
holding that single monitor — so the check-then-act sequence is one
indivisible critical section from any other thread's perspective. A second
thread calling `dispenseOne()` concurrently simply blocks until the first
thread's full method (check + decrement) completes, at which point it sees
the *updated* `quantity`, not the stale value the first thread started
with. This is exactly what prevents the classic TOCTOU race: without the
lock, both threads could read `quantity == 1` before either writes, and
both would proceed to decrement, resulting in `quantity == -1` (or an
`IndexOutOfBounds`-style double-dispense from a single unit).

**24. Could `CashBox.withdrawForChange` deadlock with `Slot.dispenseOne` under any interleaving?**
No — they are never both acquired in a nested fashion in either order
anywhere in the codebase. `CashPaymentStrategy.settle` calls
`CashBox.withdrawForChange` and *returns* before `HasMoneyState` later calls
`slot.dispenseOne()` — the `CashBox` lock is fully released before the
`Slot` lock is ever requested in that code path. Since no method holds one
while waiting on the other, there's no cycle to form, regardless of how many
threads interleave.

**25. Your concurrency test uses a `CountDownLatch` to line up threads before they race — why not just submit them to the executor and let them race naturally?**
Without the `ready`/`go` latch pair, thread start-up jitter means most
submitted tasks would actually run sequentially or with minimal overlap on
a typical test machine (thread pool warm-up, OS scheduling), making the test
"usually pass even with a buggy, unsynchronized implementation" — a false
negative waiting to happen. The latch pair forces every thread to be alive
and waiting at the same instruction, then releases them all at once, which
maximizes actual concurrent overlap and makes the test a *meaningful*
negative control: if I removed `synchronized` from `Slot.dispenseOne`, this
specific test would reliably (not just occasionally) start failing.

---

## Database and Persistence

**26. You said "no database needed" — what's the actual threshold that would change your mind?**
Two independent triggers, either one sufficient: (a) a requirement that
sales/inventory state must survive a process crash or power cycle (named
explicitly in README §13's "mid-dispense crash" gap), or (b) a requirement
that more than one process/machine needs to query or reconcile this
machine's transaction history (e.g. a central reporting dashboard). Neither
is in scope today (§1 names both as explicitly out of scope), so adding a
database now would be solving a problem that hasn't been asked for.

**27. If you did add persistence, what isolation level would you actually need for the `slot_state` + `transactions` write, and why not something weaker?**
At minimum `REPEATABLE READ`, and realistically you'd want the dispense
recording and the slot-quantity decrement to be in the *same* transaction
regardless of isolation level, because the real risk isn't concurrent
readers seeing inconsistent data — it's a crash between two separate
commits leaving them permanently out of sync (the exact gap named in
STAFF_LEVEL_DEEP_DIVE Q8). Isolation level addresses concurrent transaction
visibility; atomicity (one transaction, not two) addresses the crash-gap,
and you need both, not one instead of the other.

---

## Performance

**28. Where's the one place in this design where you traded a small, deliberate performance cost for correctness/simplicity, and would you make the same trade again?**
`CashBox.withdrawForChange` copies the entire `counts` map
(`new EnumMap<>(counts)`) before calling `changeMaker.computeChange` and
only mutates the real map after a success. That's an extra allocation (of a
map with ≤8 entries) on every single change computation. I'd make the same
trade again: the alternative (mutate in place, then undo on failure) is more
error-prone to get right for an operation that runs, at most, a few times a
minute on one physical machine — the performance cost is immeasurably small
and the correctness clarity ("failure means zero observable mutation,
period") is worth it outright.

---

## Failure Handling

**29. What's the difference in how you handle "customer error" failures (insufficient funds) versus "system error" failures (change can't be made), and why should they be handled differently?**
Customer-correctable failures (insufficient funds, wrong slot selected)
leave the machine exactly where it was and let the customer try again
within the same session — no refund needed because nothing was ever
committed. System-level failures (change can't be made, card gateway down)
are *not* something the customer can fix by trying again in the same
session, so the only safe response is a full abort with a refund — holding
the customer's money hostage while the *machine's* problem gets sorted out
is never acceptable. The exception type names this distinction directly:
`InsufficientFundsException` extends the base type but is handled
completely differently in `HasMoneyState.selectProductForCash` (let it
propagate, no cleanup) than `InsufficientChangeException` (explicit
refund-then-rethrow).

---

## Production Architecture

**30. If you had to expose this machine's admin operations over a network API (REST/gRPC) for a remote management console, what changes, and what stays exactly the same?**
`AdminService`'s method signatures stay exactly the same — they already
take simple, serializable parameters (`String slotId`, `Money`, `Map<Denomination,Integer>`).
What changes: you'd add a thin controller/handler layer that deserializes
requests into those same parameter types, adds authentication/authorization
(today entirely absent, as noted in README §17), and maps domain exceptions
(`ProductNotFoundException`, etc.) to appropriate HTTP/gRPC status codes.
Critically, none of `VendingMachine`, the state classes, or the payment
strategies would need to change at all — the network boundary sits entirely
outside the domain model, which is exactly the payoff of having kept
`AdminService` free of any transport-specific concerns from the start.
