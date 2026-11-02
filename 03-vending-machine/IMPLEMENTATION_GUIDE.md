# Implementation Guide — Vending Machine

A practical, in-order build guide: what to write first, what the minimal
compilable version looks like, and how to narrate it live.

---

## Which classes to write first

Build bottom-up, from zero-dependency value objects to the orchestrating
facade — this order ensures everything you write compiles immediately and
is independently testable before you need the next layer:

1. `Money` — no dependencies at all.
2. `Denomination` — no dependencies.
3. `Product` — depends only on `Money`.
4. `Slot` — depends on `Product` (+ its own exception).
5. `Inventory` — depends on `Slot`.
6. `ChangeMaker` (interface) + `GreedyChangeMaker` — depends on `Denomination`/`Money`.
7. `CashBox` — depends on `Denomination`/`Money`/`ChangeMaker`.
8. All exception classes — can be written any time before the first thing
   that throws them; grouping them together is fine.
9. `CardPaymentGateway` (interface) + `MockCardPaymentGateway`.
10. `VendingMachineContext` (interface) — the seam, write it before any state class.
11. `VendingMachineState` (interface, with default-throw methods).
12. `PaymentStrategy` (interface) + `PaymentOutcome`.
13. `CashPaymentStrategy`, `CardPaymentStrategy` — depend on #10 and #12.
14. `IdleState`, `HasMoneyState` — the two states carrying real logic; depend on #11–13.
15. `DispensingState`, `OutOfStockState`, `MaintenanceState` — small, mechanical.
16. `Transaction`, `TransactionLog` — can be written any time before #17.
17. `VendingMachine` — wires everything together, implements `VendingMachineContext`.
18. `AdminService` — depends on `VendingMachine` existing.
19. A `Demo`/`Main` class exercising the flows end to end.

## A minimal compilable implementation outline

If you only have time for a skeleton that compiles and demonstrates the
shape of the design (not full business logic), this is the smallest useful
version:

```java
// Money.java
public record Money(long cents) {
    public Money add(Money o) { return new Money(cents + o.cents); }
    public Money subtract(Money o) { return new Money(cents - o.cents); }
}

// Product.java
public record Product(String id, String name, Money price) {}

// Slot.java
public class Slot {
    private final Product product;
    private int quantity;
    public Slot(Product p, int q) { this.product = p; this.quantity = q; }
    public synchronized Product dispenseOne() {
        if (quantity == 0) throw new IllegalStateException("empty");
        quantity--;
        return product;
    }
}

// VendingMachineState.java
public interface VendingMachineState {
    default void insertCoin(VendingMachine m, int cents) {
        throw new UnsupportedOperationException();
    }
    default void select(VendingMachine m, String slotId) {
        throw new UnsupportedOperationException();
    }
}

// IdleState.java / HasMoneyState.java — two small classes implementing
// only the operations they support, as sketched in README section 9.

// VendingMachine.java — holds a Map<String, Slot>, a running balance,
// and a `VendingMachineState currentState`, delegating every public
// method to currentState.
```
This skeleton alone demonstrates: value objects for money, a state-owning
`Slot` with the dispense race already guarded, and the State pattern shape
— enough to have a real conversation about the design even if you never
get to payment strategies or change-making.

## Suggested implementation sequence (live-coding pacing)

1. **Minute 0–3:** `Money` — write it, say the `BigDecimal`-vs-cents
   trade-off out loud in one sentence, move on.
2. **Minute 3–8:** `Denomination`, `Product`, `Slot` — pause on `Slot.dispenseOne`
   specifically to say "this is where I'll put the concurrency guard" even
   before discussing concurrency in depth.
3. **Minute 8–12:** `Inventory` — quick, mechanical.
4. **Minute 12–18:** `ChangeMaker`/`GreedyChangeMaker` — narrate the
   canonical-denomination assumption as you write the loop.
5. **Minute 18–25:** `CashBox` — this is where you introduce the lock;
   narrate the deposit/withdraw/reverse trio together as one coherent unit.
6. **Minute 25–35:** `VendingMachineState`/`VendingMachineContext` interfaces,
   then `IdleState`/`HasMoneyState` — the heart of the design; take your
   time here, this is what's actually being evaluated.
7. **Minute 35–42:** `PaymentStrategy`/`CashPaymentStrategy`/`CardPaymentStrategy`
   — wire them into the states you just wrote.
8. **Minute 42–48:** `VendingMachine` facade — this is where it all clicks
   together; run a mental (or real, if you have a REPL) trace of one
   purchase.
9. **Minute 48–55:** Remaining states (`Dispensing`/`OutOfStock`/`Maintenance`),
   `AdminService` — fast, these are short.
10. **Minute 55–60:** Concurrency/testing discussion, trade-offs, wrap-up.

## Important methods (the ones that carry real logic — know these cold)

- `Slot.dispenseOne()` — the check-then-act concurrency guard.
- `CashBox.withdrawForChange(Money, ChangeMaker)` — atomic compute-then-deduct.
- `GreedyChangeMaker.computeChange(Money, Map)` — the algorithm itself.
- `CashPaymentStrategy.settle(Money, VendingMachineContext)` — the
  deposit-then-attempt-then-reverse-on-failure sequencing.
- `HasMoneyState.selectProductForCash(...)` — the central orchestration:
  stock check → payment settle → dispense → state transition.
- `VendingMachine`'s public methods — each is `lock() { delegate to state } unlock()`,
  nothing more; if you find yourself putting business logic directly in
  `VendingMachine`, that's a signal you've broken the Facade/State split.

## Common coding mistakes (and how this design avoids them)

- **Using `double` for money.** Avoided by `Money` wrapping `long` cents from
  the start — say this out loud as a deliberate choice, don't wait to be
  asked.
- **Checking stock/funds, then dispensing in a separate, unguarded step**
  (TOCTOU). Avoided by making `dispenseOne()` itself the atomic
  check-and-decrement, not two separate calls (`isEmpty()` then `decrement()`)
  that a caller could interleave incorrectly.
- **Dispensing before confirming payment succeeded.** Avoided by a strict
  call order in every state class: `strategy.settle(...)` always happens
  before `slot.dispenseOne()`, never the reverse — say this explicitly, it's
  a common place candidates get the order backwards under time pressure.
- **Forgetting the refund path entirely.** A common shortcut under time
  pressure is to implement the happy path and skip "what if change can't be
  made" — this design treats that as a first-class failure mode with its
  own test, not an afterthought.
- **One giant lock around everything "to be safe."** Avoided by explicitly
  reasoning about *what* needs protecting (front panel session vs. slot
  quantity vs. cash float) and giving each its own lock — a single global
  lock would "work" but signals you didn't think about granularity.
- **Forgetting that `enum.values()` iteration order is declaration order.**
  `GreedyChangeMaker` depends on `Denomination` being declared in descending
  value order — call this out explicitly as an implicit contract between
  the enum and the algorithm, since it's an easy thing to silently break by
  reordering the enum later.

## Edge cases (rank-ordered, as in the Playbook, repeated here for implementation focus)

1. Selecting an empty slot — must not touch balance or state.
2. Insufficient funds — must not touch inventory or cash box.
3. Insufficient change — must reverse any already-applied cash box mutation
   before rethrowing.
4. Whole-machine empty — must transition state, not just leave it to be
   inferred.
5. Maintenance entry while a transaction is active — must be structurally
   impossible, not just "checked with an if."
6. Restock recovering from `OutOfStockState` — must be wired explicitly
   (`AdminService.restock` calling `notifyInventoryReplenished`), it does
   not happen automatically just because a `Slot`'s count changed.

## How to narrate the code while writing it

Say the *reason* before the *code*, every time, in one sentence:
- "I'm making `Money` a record because it's a pure value with no identity."
- "I'm making `dispenseOne` synchronized because the check and the
  decrement have to be one atomic step, not two."
- "I'm throwing from a default interface method instead of writing a
  switch, because I want every state class to only define what it
  *does* support."
- "I'm reversing the deposit here because I never want the cash box to end
  up holding money that didn't actually buy anything."
Narrating the *why* before the *what* is what separates "I know this
pattern" from "I'm applying this pattern because it's actually the right
tool here" in an interviewer's eyes.

## What can be safely omitted under time pressure

- The DP change-making fallback — describe it verbally (README §10), do not
  implement it; greedy + a documented assumption is sufficient.
- `TransactionLog`/audit trail — mention it, implement only if there's time
  left over; nothing else depends on it.
- `AdminService.collectCash`'s float-preserving logic — a simpler
  "collect everything" version is fine to mention as a placeholder.
- `MaintenanceState` — can be described verbally as "a state that blocks
  everything except exit" without full implementation if truly squeezed for
  time; this is lower-value than the cash purchase happy path and the
  concurrency discussion.
- Full exception hierarchy — a flatter set of 2-3 exception types is
  acceptable under real time pressure; the *behavior* (recoverable vs.
  abort-and-refund) matters more than having one exception class per
  failure mode.

**Never omit, even under pressure:** the pay-before-dispense ordering, the
`Slot.dispenseOne()` atomicity, and a verbal (if not coded) answer to "is
concurrent access even real here" — these three are the substance of the
problem; everything else is scaffolding around them.

## A simplified version implementable in 20–30 minutes

Scope: cash-only, exact-change-only (no `ChangeMaker`/`CashBox`), single
`enum MachineState` with a `switch` instead of the full State pattern, no
card payment, no admin service beyond a bare `restock(slotId, qty)` method
directly on the machine.

```java
public record Money(long cents) {
    public Money add(Money o) { return new Money(cents + o.cents); }
}

public class Slot {
    final String id;
    final Money price;
    int quantity;
    Slot(String id, Money price, int quantity) {
        this.id = id; this.price = price; this.quantity = quantity;
    }
    synchronized boolean tryDispense() {
        if (quantity == 0) return false;
        quantity--;
        return true;
    }
}

public class SimpleVendingMachine {
    enum State { IDLE, HAS_MONEY }
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private State state = State.IDLE;
    private Money balance = new Money(0);
    private final Object lock = new Object();

    void insertCoin(long cents) {
        synchronized (lock) {
            balance = balance.add(new Money(cents));
            state = State.HAS_MONEY;
        }
    }

    String selectProduct(String slotId) {
        synchronized (lock) {
            Slot slot = slots.get(slotId);
            if (slot == null) throw new NoSuchElementException("no such slot");
            if (balance.cents() < slot.price.cents()) {
                throw new IllegalStateException("insufficient funds");
            }
            if (balance.cents() != slot.price.cents()) {
                throw new IllegalStateException("exact change only, no change given");
            }
            if (!slot.tryDispense()) throw new IllegalStateException("out of stock");
            balance = new Money(0);
            state = State.IDLE;
            return slotId + " dispensed";
        }
    }
}
```
This compiles standalone, demonstrates the core invariant (pay before
dispense, atomic dispense), and is a legitimate answer to "show me something
working" in the first 20 minutes — then you talk through everything this
simplified version is missing (change-making, card payment, the full State
pattern, admin operations, the refund path) as the natural "here's what I'd
add next" continuation.
