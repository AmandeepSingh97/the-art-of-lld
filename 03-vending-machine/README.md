# Vending Machine — Low-Level Design

A Staff-level, interview-oriented LLD for a single physical vending machine:
product slots, cash and card payment, change-making, a State-pattern machine
lifecycle, and admin/restocking operations.

All code referenced below lives under
`src/main/java/lld/vendingmachine/` and is exercised by tests under `tests/`.
Diagrams live under `diagrams/` and are reproduced inline here.

---

## 1. Problem Understanding

**Plain explanation.** Design the software that runs inside a single vending
machine. A customer walks up, inserts coins/bills or taps a card, selects a
product from a numbered slot, and the machine either dispenses the product
(plus change, for cash) or rejects the request (out of stock, insufficient
funds, payment declined). An operator can restock slots, reprice products,
collect cash, and put the machine into maintenance mode.

**Real-world use case.** This is the software model for any single-unit
unattended retail kiosk: snack/drink machines, laundromat payment units,
parking ticket dispensers, claw machines with a change module. The shared
shape is: *limited physical inventory + a payment-collection device + a
dispense actuator + an operator channel*.

**What the interviewer is evaluating.**
- Can you model physical state (money inserted, slot inventory) as object
  state without leaking primitives everywhere?
- Do you reach for the State pattern naturally, and can you justify *why* each
  transition exists (not just recite GoF)?
- Do you understand what "concurrency" even means for a single physical
  device, instead of reflexively adding locks everywhere or claiming "it's
  single-threaded, no locking needed" (both are wrong — see §11)?
- Do you handle money as an exact, non-floating-point type?
- Do you handle the change-making algorithm correctly, including the failure
  case (no exact change possible) and the required refund behavior?
- Do you separate customer-facing operations from admin operations (ISP)?

**In-scope.**
- Single machine, multiple slots, each slot holds one product type.
- Cash payment (coins + bills) with incremental insertion, selection, and
  exact-change dispensing.
- Card payment (single tap-and-charge, no change).
- State machine: Idle, HasMoney/Selecting, Dispensing, OutOfStock, Maintenance.
- Change-making algorithm with a documented correctness assumption and a
  swappable `ChangeMaker` strategy.
- Admin operations: restock, reprice, cash collection, enter/exit maintenance.
- Cancel with exact-cash refund.
- Thread-safety analysis of a "single device, many actors" system (customer
  front panel, async hardware sensors, remote admin console).

**Out-of-scope** (named explicitly, see §17 for where each would go).
- Fleet management across many physical machines (a distributed systems
  problem, not an LLD problem).
- Real payment network integration (PCI compliance, tokenization).
- Hardware driver details (motor PWM signals, coin validator electronics).
- Persistent storage / durability across machine power loss (addressed at the
  design level in §12, not implemented).
- A UI/front-panel renderer — `VendingMachine` is the API a UI would call.

---

## 2. Requirement Gathering

### Functional — essential
1. Maintain an inventory of products, each in one or more slots with a
   quantity and capacity.
2. Accept cash (coins and bills) incrementally and track a running balance.
3. Accept a single card tap that charges the exact price.
4. Let the customer select a product; dispense it if payment succeeds.
5. Compute and dispense exact change for cash purchases.
6. Let the customer cancel and get their inserted cash back exactly.
7. Reject selection of an empty slot without destabilizing the rest of the
   transaction.
8. Detect when the entire machine is out of stock and refuse new
   transactions until restocked.
9. Let an operator restock a slot, reprice a product, collect cash, and put
   the machine into maintenance (which blocks all customer operations).

### Functional — optional / nice-to-have
- Multiple currencies.
- Loyalty/discount codes.
- Partial-change tolerance (e.g. permit exact-cash-only mode when the float
  is low, announced to the customer before they insert money).
- Telemetry push to a remote fleet-management backend.
- Receipt printing.

### Non-functional
- **Performance.** All operations are in-memory; an operation (insert coin,
  select product) must complete in well under human-perceptible time
  (effectively O(1)–O(slots) per call — see §10).
- **Scalability.** A single machine has a fixed, small number of slots (tens)
  and a fixed, small denomination set (≤ 10). Scalability concerns here are
  about *correctness under concurrent access*, not throughput — see §11's
  explicit discussion of whether concurrency is even realistic for one
  machine.
- **Availability.** The machine should fail toward "refuse new transactions
  safely" rather than silently losing money or dispensing without payment.
- **Consistency.** The invariant "money collected == price charged, product
  dispensed == 1 unit removed from exactly one slot" must never be violated,
  even under a crash mid-transaction (see §13).
- **Extensibility.** Adding a payment method or swapping the change algorithm
  must not require touching the state machine (Strategy pattern, §7).
- **Reliability.** A failed payment or failed change-making must leave the
  machine in a safe, inventory-and-cash-consistent state — never a half
  completed sale.
- **Thread-safety.** Explicit, justified locking strategy per shared mutable
  resource (§11), not a single global lock "just in case."

### Interview-necessary vs. production-extension split
| Interview-necessary (this repo implements it) | Production-extension (discussed, not implemented) |
|---|---|
| State pattern machine lifecycle | Hardware driver integration / async sensor events |
| Cash + card payment strategies | Real PCI-compliant payment gateway |
| Greedy change-making + documented DP fallback | Remote fleet inventory sync |
| In-memory inventory/cash box with correct locking | Persistent/durable storage across power loss |
| Admin restock/reprice/collect/maintenance | Full RBAC admin auth, audit compliance tooling |
| Transaction log (in-memory) | Metrics/alerting pipeline, crash-recovery WAL |

---

## 3. Clarifying Questions

These are the questions a candidate should ask in the first five minutes, with
why they matter and how the answer changes the design. (These are also the
assumptions this implementation makes, documented here per the "Clarifying
Questions" requirement.)

1. **Does the machine need to make change, or is it exact-cash-only?**
   *Why it matters:* if exact-cash-only, you drop `ChangeMaker`/`CashBox`
   entirely and the design is much simpler. *Answer assumed:* the machine
   does make change, because that's the harder and more realistic case, and
   it's explicitly called out in the scope guidance.
2. **Can a customer mix payment methods in one transaction (insert some cash,
   then tap a card for the rest)?**
   *Why it matters:* this changes `PaymentStrategy` from "pick one, fully
   settle" to "partial settlement, running ledger across strategies," a much
   bigger surface area. *Answer assumed:* no — pick cash or card per
   transaction; switching requires cancel-and-restart. This is documented
   directly in `HasMoneyState`'s Javadoc.
3. **Is the machine single-slot-per-product or can one product span several
   slots (for load balancing / extra capacity)?**
   *Why it matters:* determines whether `Inventory` needs a
   product→slots index for "find any slot with stock" logic. *Answer
   assumed:* slot-addressed selection only (customer presses "A1", not
   "Coke") — matches real vending machines and keeps `Inventory` a flat map.
4. **What currency/denomination set, and is it fixed or configurable per
   deployment?**
   *Why it matters:* determines whether greedy change-making is provably
   correct (canonical system) or needs a DP fallback. *Answer assumed:*
   standard US cents denominations (fixed, canonical) — see §10 for the
   proof sketch and what changes otherwise.
5. **Does "out of stock" mean one slot or the whole machine, and should the
   machine proactively stop accepting money when completely empty?**
   *Why it matters:* this is literally called out in the state list
   (Idle/HasMoney/Dispensing/**OutOfStock**/Maintenance) — if OutOfStock were
   per-slot only, it wouldn't be a top-level machine state. *Answer assumed:*
   both exist: per-slot stock-outs are a recoverable error within
   `HasMoneyState`; whole-machine stock-out is a dedicated top-level state
   that blocks all new transactions.
6. **Is concurrent access to one physical machine a real requirement, or is
   this actually about a multi-machine fleet sharing a backend?**
   *Why it matters:* this changes the entire locking strategy — see the
   dedicated discussion in §11. *Answer assumed:* the question is taken
   seriously and answered explicitly rather than hand-waved: a single
   machine's front panel is physically single-session, but the cash box and
   inventory are touched by more than just the front panel (admin console,
   hardware sensors), so some concurrency is real even on one device.
7. **Does cancel refund the running balance as cash value, or the exact coins
   inserted?**
   *Why it matters:* refunding "value" could mean giving back different
   (even larger) denominations than inserted, which is both physically wrong
   (a machine's coin return mechanically ejects what's in the acceptor path)
   and a cash-float risk. *Answer assumed:* exact coins/bills inserted are
   returned, never substituted — implemented via `insertedDenominations`
   tracked separately from the box's committed float.
8. **Should maintenance mode be enterable while a customer transaction is in
   progress (e.g. to force-clear a jam)?**
   *Why it matters:* allowing it means designing an operator-override/force
   path that can abandon a paid-for transaction — a money-safety question.
   *Answer assumed:* no, maintenance can only be entered from `IdleState`;
   an operator must wait for the current session to finish (or the customer
   cancels) first. This is enforced structurally (every other state's
   default `enterMaintenance` throws) rather than by a runtime flag.

---

## 4. Domain Modelling

| Type | Kind | Responsibility |
|---|---|---|
| `Money` | Value object (record) | Exact integral-cents monetary amount; arithmetic with no negative results. |
| `Denomination` | Enum | The physical coin/bill units the machine understands, in descending value order. |
| `Product` | Entity | Catalog item: id, name, mutable price. |
| `Slot` | Entity | Owns a product reference + mutable quantity/capacity; the unit of concurrency control for dispensing. |
| `Inventory` | Domain service / repository | Slot lookup, whole-machine-empty check. |
| `CashBox` | Domain service | The physical cash float; owns deposit/withdraw-for-change/collect, all made atomic via a lock. |
| `ChangeMaker` (interface) + `GreedyChangeMaker` | Strategy | Computes a denomination breakdown for a change amount. |
| `PaymentStrategy` (interface) + `CashPaymentStrategy`, `CardPaymentStrategy` | Strategy | Finalizes payment for a given price; hides cash-vs-card mechanics from the state machine. |
| `CardPaymentGateway` (interface) + `MockCardPaymentGateway` | External service abstraction | Authorizes a card charge; swappable for a real processor. |
| `VendingMachineState` (interface) + 5 concrete states | State | One class per lifecycle state; each knows only its own legal transitions. |
| `VendingMachineContext` (interface) | Context seam | The narrow API `VendingMachineState` implementations use to read/mutate the machine — Interface Segregation between "state logic" and "full machine facade." |
| `VendingMachine` | Facade / Context / Aggregate root | Implements `VendingMachineContext`; the only class a UI/test talks to; owns the front-panel lock. |
| `Transaction`, `TransactionLog` | Value object + repository | Append-only audit trail of completed purchases. |
| `AdminService` | Application service | Operator-only operations, kept out of the customer-facing API. |

**Composition / association / ownership.**
- `VendingMachine` **composes** `Inventory`, `CashBox`, `TransactionLog` — their
  lifecycle is bound to the machine; they are created with it and never
  shared across machines in this design.
- `Inventory` **composes** `Slot`s (1..*); a `Slot` does not outlive its
  inventory.
- `Slot` **associates** with a `Product` (not composition): the same
  `Product` instance can legitimately be referenced by more than one `Slot`
  (two coils of the same drink), so a slot does not own the product's
  lifecycle.
- `VendingMachine` **uses** (dependency, not ownership) a `ChangeMaker` and a
  `CardPaymentGateway` — both are injected via the constructor, enabling
  substitution in tests (`MockCardPaymentGateway`) and in production (a real
  gateway) without touching `VendingMachine`.
- `PaymentStrategy` implementations are **stateless** (no fields beyond
  behavior) — instantiated fresh or reused freely; there's deliberately no
  "current payment strategy" field stored on the machine, because payment
  method is chosen per call (`selectProductForCash` vs `payByCard`), not
  pre-selected machine-wide state.
- `AdminService` **wraps** a `VendingMachine` reference; it does not inherit
  from it and cannot be substituted where a `VendingMachine` is expected —
  deliberate ISP: a keypad UI should not be able to compile against
  `restock()`.

---

## 5. Domain Invariants

Business rules that must always hold, and where they are enforced:

1. **A slot's quantity is never negative and never exceeds capacity.**
   Enforced inside `Slot` itself (`dispenseOne`, `restock`,
   `reassignProduct` all validate), under `synchronized` — the invariant is
   owned by the object that can violate it, not by callers remembering to
   check.
2. **Money is never negative.** Enforced in `Money`'s compact constructor and
   in `subtract`, which throws rather than silently clamping to zero (a
   silent clamp would hide a bug where a refund exceeds a balance).
3. **A product is only dispensed if its price has been fully collected (cash
   balance ≥ price, or card charge succeeded) *before* the slot is
   decremented.** Enforced by the call order in both `HasMoneyState` and
   `IdleState.payByCard`: `PaymentStrategy.settle(...)` always happens before
   `slot.dispenseOne()`. There is no code path that reverses this order.
4. **The cash box's committed counts must never reflect money that was
   ultimately refunded.** Enforced by `CashPaymentStrategy`'s
   deposit-then-attempt-withdraw-then-reverse-on-failure sequencing (see
   §13, Failure Scenarios, for the detailed walk-through).
5. **A customer's inserted cash is refunded as the exact denominations
   inserted, never substituted from the box.** Enforced by
   `VendingMachine.refundInsertedCash`, which only ever clears/returns
   `insertedDenominations` and never calls into `CashBox` for a cancel.
6. **Only one state is "current" at a time, and every operation goes through
   it.** Enforced by `VendingMachine.currentState` being the single source of
   truth, mutated only via `VendingMachineContext.transitionTo`, itself only
   called from within state classes while `machineLock` is held.
7. **The machine stops accepting new customer transactions the instant every
   slot is simultaneously empty**, and resumes the instant any slot is
   restocked. Enforced by the `Dispensing`→`OutOfStock` transition check
   (`inventory.isCompletelyEmpty()`) and `AdminService.restock` calling
   `machine.notifyInventoryReplenished()` after every restock.
8. **Maintenance mode can only be entered when no customer transaction is in
   flight.** Enforced structurally: only `IdleState` and `OutOfStockState`
   override `enterMaintenance`; every other state inherits the default
   "unsupported" behavior from `VendingMachineState`.

---

## 6. Architecture and Class Diagram

### Component diagram
See [`diagrams/component-diagram.md`](diagrams/component-diagram.md).

### Class diagram
See [`diagrams/class-diagram.md`](diagrams/class-diagram.md) — this is a
direct reflection of the classes under `src/main/java/lld/vendingmachine`;
no diagram-only abstractions were invented.

### State diagram
See [`diagrams/state-diagram.md`](diagrams/state-diagram.md).

### Sequence diagrams
- Cash purchase with change:
  [`diagrams/sequence-cash-purchase.md`](diagrams/sequence-cash-purchase.md)
- Card purchase and insufficient-change refund:
  [`diagrams/sequence-card-and-refund.md`](diagrams/sequence-card-and-refund.md)

---

## 7. Design Patterns

For each pattern: why needed, where applied, alternative considered, why the
alternative was rejected.

### State pattern — `VendingMachineState` + 5 concrete classes
- **Why needed.** The machine's legal operations genuinely differ by
  lifecycle phase (you cannot insert a coin during `Maintenance`, you cannot
  select a product during `Dispensing`), and the number of (state × operation)
  combinations is large enough (5 states × 7 operations) that encoding it as
  `if` chains inside `VendingMachine` would create exactly the kind of
  "giant switch on an enum" code smell the pattern exists to avoid.
- **Where applied.** `state/` package; `VendingMachine.currentState` field,
  `transitionTo` is the only mutator.
- **Alternative considered.** A single `MachineState` enum with a big
  `switch` in `VendingMachine` for every operation.
- **Why rejected.** It would put all five states' logic in one class
  (violates SRP), make it easy to forget a case when adding a new state (no
  compiler help — enum switches don't force exhaustiveness the way
  implementing an interface does), and scatter the "why is this transition
  allowed" reasoning across one giant method instead of co-locating it with
  each state.

### Strategy pattern — `PaymentStrategy` and `ChangeMaker`
- **Why needed.** Two independent axes vary: how payment is finalized (cash
  vs. card) and how change is computed (greedy vs. DP for non-canonical
  denominations). Both are exactly the "family of interchangeable
  algorithms" Strategy targets.
- **Where applied.** `payment/PaymentStrategy` (+`CashPaymentStrategy`,
  `CardPaymentStrategy`), `change/ChangeMaker` (+`GreedyChangeMaker`).
- **Alternative considered.** Inheritance — `CashVendingMachine extends
  VendingMachine`, `CardVendingMachine extends VendingMachine`.
- **Why rejected.** Payment method is a per-transaction choice, not a
  per-machine-instance one in this design (the clarifying answer in §3 says
  mixing is disallowed *per transaction*, not that a machine only ever
  accepts one method). Subclassing the whole machine per payment method
  would also combinatorially explode if crossed with states. Composition
  keeps `VendingMachine` a single class that *uses* a strategy, rather than
  *being* one.

### Facade pattern — `VendingMachine`
- **Why needed.** A UI (keypad controller) or test should not need to know
  about `Inventory`, `CashBox`, `ChangeMaker`, `CardPaymentGateway`, and the
  current `VendingMachineState` object separately — it needs one coherent
  entry point with a small, intention-revealing method set.
- **Where applied.** `core/VendingMachine` is the sole public API surface for
  customer operations; see the Demo class, which only ever calls methods on
  `VendingMachine` and `AdminService`.
- **Alternative considered.** Expose `Inventory`/`CashBox`/state directly to
  callers.
- **Why rejected.** Would leak internal invariants (e.g. callers could call
  `slot.dispenseOne()` directly, bypassing payment) and couple callers to
  internal structure that should be free to change.

### Value Object pattern — `Money`, `PaymentOutcome`, `Transaction`
- **Why needed.** Money arithmetic must be exact, immutable, and
  equality-by-value (two `$1.25`s are interchangeable); using `double` or a
  mutable class here is a classic, costly mistake.
- **Where applied.** `Money` is a `record` over `long` cents; `Transaction`
  and `PaymentOutcome` are also records.
- **Alternative considered.** `BigDecimal` for money.
- **Why rejected.** `BigDecimal` is the *right* choice for arbitrary-scale,
  arbitrary-precision production money (it's what most real systems use),
  but it is heavier (more allocations, scale/rounding-mode bookkeeping) than
  this bounded-denomination domain needs; integral cents are simpler, faster,
  and exactly as correct for a currency with a fixed minor unit. **This is
  a deliberate trade-off, documented rather than silently chosen** — a
  reviewer who insists on `BigDecimal` for "future multi-currency support" is
  raising a legitimate point (see Trade-offs §16).

### What was deliberately *not* used
- **No Singleton for `VendingMachine`.** Tests instantiate many independent
  machines (see `tests/.../VendingMachineTest`); a Singleton would make
  testing painful for a benefit (global uniqueness) the design doesn't need —
  "one machine" is naturally enforced by whoever composes `main()` for a real
  deployment, not by the type system.
- **No Factory for `Product`/`Slot`.** Construction is a two-or-three-argument
  constructor call with no conditional logic or product family selection —
  a Factory here would be ceremony with no payoff (an anti-pattern the spec
  explicitly warns against).
- **No Observer/pub-sub for state transitions.** Nothing in-scope needs to
  react to a transition asynchronously (no UI to repaint, no metrics
  pipeline in the interview-scope solution); adding one now would be
  speculative generality. §17 notes where this *would* be added in
  production (telemetry).

---

## 8. API and Method Design

### Customer-facing (`VendingMachine`)
```java
public void insertCoin(Denomination denomination);

public void selectProductForCash(String slotId)
        throws InsufficientFundsException, InsufficientChangeException,
               SlotOutOfStockException, ProductNotFoundException;

public void payByCard(String slotId)
        throws PaymentDeclinedException, SlotOutOfStockException, ProductNotFoundException;

public void cancel();

public String getCurrentStateName();
public Money getCurrentBalance();
```
**Validation responsibilities:** `VendingMachine` does *not* validate
business rules itself — it delegates to `currentState`, which is the single
place each rule is checked (e.g. "is this slot empty" lives in
`HasMoneyState`/`IdleState`, not duplicated in `VendingMachine`). This keeps
`VendingMachine` a thin dispatcher, consistent with the Facade's job being
"provide one entry point," not "contain logic."

### Admin-facing (`AdminService`)
```java
public void restock(String slotId, int additionalUnits);
public void reprice(String slotId, Money newPrice);
public void loadCashFloat(Denomination denomination, int count);
public Map<Denomination, Integer> collectCash(Map<Denomination, Integer> floatToKeep);
public void enterMaintenance();
public void exitMaintenance();
```

### Exceptions and when each is raised
| Exception | Raised when | Recoverable in place? |
|---|---|---|
| `ProductNotFoundException` | Unknown slot id requested | Yes — same state, pick another slot |
| `SlotOutOfStockException` | Requested slot has quantity 0 | Yes — same state (`HasMoneyState`) |
| `InsufficientFundsException` | Cash balance < price | Yes — stay in `HasMoneyState`, insert more |
| `InsufficientChangeException` | Box cannot represent the change amount | No — transaction aborted, auto-refund, back to `Idle` |
| `PaymentDeclinedException` | Card gateway declines | No — transaction aborted, no state change (stays `Idle`) |
| `InvalidStateTransitionException` | Operation not legal in current state | No — caller must use the right operation for the current state |

### Request/response objects
- No DTOs in the interview-scope API — parameters are primitives/value
  objects (`Denomination`, `String slotId`, `Money`) since this is a
  same-process facade, not a network boundary. §17 notes where request/
  response DTOs would appear if this were exposed over a REST/gRPC
  admin API in production.

---

## 9. Core Implementation

The important components in full, compiling Java 17. (Full files live under
`src/main/java/lld/vendingmachine/`; the excerpts below are the actual file
contents, not pseudocode.)

### `domain/Money.java`
```java
public record Money(long cents) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("Money cannot be negative: " + cents);
        }
    }

    public static Money ofCents(long cents) { return new Money(cents); }

    public static Money ofDollars(double dollars) {
        return new Money(Math.round(dollars * 100));
    }

    public Money add(Money other) { return new Money(this.cents + other.cents); }

    public Money subtract(Money other) {
        long result = this.cents - other.cents;
        if (result < 0) {
            throw new IllegalArgumentException(
                    "Subtraction would produce a negative amount: " + this + " - " + other);
        }
        return new Money(result);
    }

    public boolean isGreaterThanOrEqualTo(Money other) { return this.cents >= other.cents; }
    public boolean isZero() { return this.cents == 0; }

    @Override public int compareTo(Money other) { return Long.compare(this.cents, other.cents); }
    @Override public String toString() { return String.format("$%d.%02d", cents / 100, cents % 100); }
}
```

### `domain/Slot.java` (dispense is the concurrency-critical method)
```java
public final class Slot {
    private final String slotId;
    private final int capacity;
    private Product product;
    private int quantity;

    // ... constructor omitted for brevity, see src/ ...

    public synchronized Product dispenseOne() {
        if (quantity == 0) {
            throw new SlotOutOfStockException(slotId);
        }
        quantity--;
        return product;
    }

    public synchronized void restock(int additionalUnits) {
        int newQuantity = quantity + additionalUnits;
        if (newQuantity > capacity) {
            throw new IllegalArgumentException("Restocking would exceed capacity");
        }
        this.quantity = newQuantity;
    }
}
```

### `change/GreedyChangeMaker.java`
```java
public final class GreedyChangeMaker implements ChangeMaker {
    @Override
    public Map<Denomination, Integer> computeChange(Money amount, Map<Denomination, Integer> available)
            throws InsufficientChangeException {
        long remaining = amount.cents();
        Map<Denomination, Integer> result = new EnumMap<>(Denomination.class);
        for (Denomination d : Denomination.values()) { // descending value order
            if (remaining == 0) break;
            int haveCount = available.getOrDefault(d, 0);
            long wantCount = remaining / d.valueInCents();
            long useCount = Math.min(haveCount, wantCount);
            if (useCount > 0) {
                result.put(d, (int) useCount);
                remaining -= useCount * d.valueInCents();
            }
        }
        if (remaining != 0) throw new InsufficientChangeException(amount);
        return result;
    }
}
```

### `payment/CashPaymentStrategy.java` (the sequencing is the whole point — see §5 invariant 4)
```java
public final class CashPaymentStrategy implements PaymentStrategy {
    @Override
    public PaymentOutcome settle(Money priceDue, VendingMachineContext context)
            throws InsufficientFundsException, InsufficientChangeException {
        Money balance = context.getBalance();
        if (balance.compareTo(priceDue) < 0) {
            throw new InsufficientFundsException(priceDue.subtract(balance));
        }
        Money changeOwed = balance.subtract(priceDue);
        List<Denomination> inserted = List.copyOf(context.getInsertedDenominations());
        context.getCashBox().deposit(inserted);

        if (changeOwed.isZero()) return PaymentOutcome.noChange(null);

        try {
            Map<Denomination, Integer> change =
                    context.getCashBox().withdrawForChange(changeOwed, context.getChangeMaker());
            return new PaymentOutcome(change, null);
        } catch (InsufficientChangeException e) {
            context.getCashBox().reverseDeposit(inserted);
            throw e;
        }
    }
}
```

### `state/HasMoneyState.java` (the central orchestration point)
```java
public final class HasMoneyState implements VendingMachineState {
    private static final CashPaymentStrategy CASH_STRATEGY = new CashPaymentStrategy();

    @Override
    public void selectProductForCash(VendingMachineContext ctx, String slotId)
            throws InsufficientFundsException, InsufficientChangeException, SlotOutOfStockException {
        Slot slot = ctx.getInventory().getSlot(slotId);
        if (slot.isEmpty()) throw new SlotOutOfStockException(slotId);

        PaymentOutcome outcome;
        try {
            outcome = CASH_STRATEGY.settle(slot.getProduct().getPrice(), ctx);
        } catch (InsufficientChangeException e) {
            ctx.refundInsertedCash();
            ctx.transitionTo(new IdleState());
            throw e;
        }

        Product dispensed = slot.dispenseOne();
        ctx.transitionTo(new DispensingState());
        ctx.dispenseAndLog(dispensed, outcome.changeToDispense(), "CASH");
        ctx.resetBalanceAndSelection();

        ctx.transitionTo(ctx.getInventory().isCompletelyEmpty() ? new OutOfStockState() : new IdleState());
    }
}
```

The remainder (`Inventory`, `CashBox`, `IdleState`, `DispensingState`,
`OutOfStockState`, `MaintenanceState`, `VendingMachine`, `AdminService`,
`VendingMachineDemo`, all exceptions) are in `src/` in full and compile
cleanly with `javac` (verified — see §15).

---

## 10. Data Structures and Algorithms

| Component | Structure | Why | Alternative | Complexity |
|---|---|---|---|---|
| `Inventory.slots` | `ConcurrentHashMap<String, Slot>` | O(1) lookup by slot id; thread-safe structural mutation (adding a slot) without a global lock | `HashMap` + external lock | O(1) average get/put |
| `CashBox.counts` | `EnumMap<Denomination, Integer>` | Dense, fixed-size key set (≤8 denominations); faster and more memory-compact than `HashMap` for enum keys, and iterates in declaration (descending-value) order for free | `HashMap<Denomination,Integer>` | O(1) get/put, O(d) iteration where d = #denominations (constant, ≤8) |
| `TransactionLog.transactions` | `CopyOnWriteArrayList<Transaction>` | Writes are rare (one per sale), reads (admin dashboard) must never block or throw `ConcurrentModificationException` | `Collections.synchronizedList` | O(n) write (copies array), O(1) iteration/read without locking |
| Change-making | Greedy, descending-value | O(d) — one pass over denominations, each a constant-time division/`min` | Dynamic programming (bounded coin change) | Greedy: O(d) time, O(d) space. DP: O(amount × d) time, O(amount) space |

### Change-making correctness, in depth

**Greedy's claim:** for the standard US denomination set `{2000, 1000, 500,
100, 25, 10, 5, 1}` (cents), always taking as many of the largest remaining
denomination as possible yields both a *feasible* solution (whenever one
exists within the available counts) and the *minimum-coin* solution. This is
because the set is **canonical**: each denomination is a multiple of (or at
least "well-behaved" relative to) the next smaller ones in a way that a
greedy choice never forecloses an optimal completion. This is a standard,
provable result for this specific denomination family (not asserted on faith
— it's the same reasoning used to justify greedy change-making in every
real-world cash register using these denominations).

**What would break it:** a configurable, non-canonical denomination set (the
classic textbook counter-example is `{1, 3, 4}` for amount `6`: greedy picks
`4 + 1 + 1` = 3 coins, but `3 + 3` = 2 coins is optimal — still feasible in
that example, but other non-canonical sets can make greedy miss feasibility
entirely even when a valid combination exists within the available counts).

**The fix, if ever needed:** a bounded coin-change dynamic program —
`dp[v]` = minimum coins to reach value `v`, iterating denominations as the
outer loop and tracking a per-denomination remaining-use budget (this is the
*bounded* knapsack variant, since `available` caps how many of each
denomination exist, unlike the textbook unbounded coin-change problem).
Complexity: `O(amount_in_cents × num_denominations)` time, `O(amount_in_cents)`
space. **The `ChangeMaker` interface exists precisely so this swap is a
one-line change** (`new GreedyChangeMaker()` → `new BoundedDpChangeMaker()`)
with zero changes to `CashPaymentStrategy` or any state class — this is the
concrete payoff of the Strategy pattern choice in §7, not just a talking
point.

---

## 11. Concurrency and Thread Safety

This section is written assuming the interviewer pushes back with exactly
the question posed in the assignment: **"Is concurrent access even realistic
for a single physical machine?"** The honest answer is: *partially* — and
the design reflects that nuance rather than picking one extreme.

### Where concurrency is **not** real
The front panel — one keypad, one coin slot, one card reader, one display —
belongs to one customer at a time. There is no scenario where two different
people are simultaneously pressing buttons on the *same physical keypad*.
This is why `VendingMachine.insertCoin` / `selectProductForCash` /
`payByCard` / `cancel` / `enterMaintenance` / `exitMaintenance` are all
guarded by **one single `ReentrantLock` (`machineLock`)** for their entire
duration. This is a legitimate, deliberate coarse-grained lock — serializing
these calls costs nothing in real parallelism because the hardware itself
already serializes them. Claiming you need per-field fine-grained locking
for the front panel would be *over-engineering a problem that doesn't exist.*

### Where concurrency **is** real, even on one machine
1. **Admin console vs. customer session.** A technician can walk up to a
   networked/IoT-connected machine and trigger a remote restock or cash
   collection *while* a customer is mid-transaction. `Slot.restock` and
   `CashBox.load`/`collectExcept` must not block on, or be blocked by,
   `machineLock` — hence they are guarded by their *own*, finer-grained
   locks (`synchronized` on the `Slot` instance; a private `ReentrantLock`
   inside `CashBox`). This is intentional lock-splitting: inventory
   structure, cash float, and front-panel state are three different
   invariants with three different locks, so an admin restocking slot B
   never contends with a customer buying from slot A.
2. **Hardware sensor callbacks.** In a real deployment, the coin validator,
   bill acceptor, and dispense-motor-complete sensor each fire on their own
   interrupt/callback thread, not on the UI thread. Our model simplifies
   this to synchronous calls inside `machineLock`, but the `DispensingState`
   class exists specifically to document the invariant such a design would
   need to preserve: *nothing else may observe or mutate machine state while
   a physical dispense is in flight.*
3. **The "is this really one machine" trap.** If the real requirement turns
   out to be *"many physical machines share one backend inventory/payment
   service"* (a fleet), then the real concurrency problem has moved to that
   backend service, not to any single `VendingMachine` instance — and that
   backend would need distributed locking/optimistic concurrency (version
   stamps, compare-and-swap on inventory rows), which is an entirely
   different (and out-of-scope, per §1) design problem. **This repo's
   `VendingMachine` models one unit correctly; it deliberately does not
   pretend to be a fleet-coordination service.**

### Per-component thread-safety guarantees

| Component | Guarantee | Mechanism | Justification |
|---|---|---|---|
| `VendingMachine` front-panel ops | Linearizable (one at a time, in acquisition order) | `machineLock` (`ReentrantLock`), held for the full call | Matches the physical reality of one keypad/session; see above |
| `Slot.quantity` | Atomic check-then-decrement (no lost updates, no overselling) | `synchronized` on the `Slot` instance | Smallest unit of true contention: an admin restock and a customer dispense on the *same* slot, or (if ever extended to multi-head) two simultaneous dispenses |
| `CashBox.counts` | Atomic compound deposit/withdraw/reverse (no overdraw, no lost updates) | Private `ReentrantLock` inside `CashBox` | Withdraw-for-change is a read-then-write compound op; two concurrent withdrawals racing on "3 quarters left" must not both succeed |
| `Inventory.slots` map structure | Thread-safe add/lookup | `ConcurrentHashMap` | Adding a brand-new slot (admin) concurrently with lookups (customer) must not corrupt the map |
| `TransactionLog` | Safe concurrent append + safe concurrent iteration | `CopyOnWriteArrayList` | Writes rare, reads (admin dashboard) must never see a `ConcurrentModificationException` or block |
| `Product.price` | Safe publication of a reprice to any thread immediately after | `volatile` field | Single-field, no cross-field invariant, so a full lock is unnecessary — `volatile` is the right amount of synchronization, not zero and not a mutex |
| `VendingMachine.currentState` (read via `getCurrentStateName()`) | Best-effort, possibly-momentarily-stale read without blocking | `volatile` field, read outside `machineLock` | A UI display poll is explicitly documented as *not* a decision input for a purchase — accepting a momentarily stale read avoids contending with every in-flight transaction just to repaint a screen |

### Deadlock analysis
There is no scenario in this design where a thread holds `machineLock` and
then blocks waiting for a `Slot`'s monitor or `CashBox`'s lock *while another
thread holds that second lock and waits on `machineLock`* — lock acquisition
order is always `machineLock` → (optionally) `Slot`/`CashBox` lock, **never**
the reverse, and `AdminService`'s direct `Slot`/`CashBox` calls never attempt
to acquire `machineLock` at all. A fixed, one-directional lock-acquisition
order is the simplest sufficient condition to rule out deadlock, and it holds
here by construction, not by accident.

### Demonstrated, not asserted
`tests/lld/vendingmachine/core/VendingMachineTest#concurrentCardPurchaseAttemptsOnLastUnitDispenseExactlyOnce`
races 20 threads against a single-unit slot and asserts exactly one succeeds
and 19 observe `SlotOutOfStockException`, with the final quantity at exactly
0 (never negative). `tests/lld/vendingmachine/domain/CashBoxTest#concurrentWithdrawalsNeverOverdrawTheFloat`
does the analogous race against the cash float. Both pass deterministically
(see §15), which is the actual evidence behind the guarantees claimed above.

---

## 12. Persistence and Database Design

**This implementation is deliberately pure in-memory, and that is the
correct interview-scope choice, not a shortcut.** Justification:

- A single vending machine's working set is tiny and bounded: tens of slots,
  ≤8 denominations, a transaction log that (for the interview scope) does
  not need to survive process restart.
- There is no multi-machine query requirement in scope (§1) that would
  demand a shared database.
- Introducing a database here would be the textbook over-engineering mistake
  the spec explicitly warns against: adding infrastructure the stated
  requirements don't need.

**If this were production** (see §17 for the full list), the one piece that
*would* need durable storage is the **transaction log and the cash-box/
inventory counts**, specifically so that a power loss or process crash does
not lose the record of "what was sold and what cash is physically in the
box." A sketch, for completeness:

```
TABLE transactions (
    transaction_id   VARCHAR PRIMARY KEY,
    machine_id       VARCHAR NOT NULL,
    product_id       VARCHAR NOT NULL,
    price_paid_cents BIGINT NOT NULL,
    payment_desc     VARCHAR NOT NULL,
    change_json      TEXT,            -- denomination -> count breakdown
    occurred_at      TIMESTAMP NOT NULL,
    INDEX idx_machine_time (machine_id, occurred_at)
)

TABLE slot_state (
    machine_id  VARCHAR,
    slot_id     VARCHAR,
    product_id  VARCHAR,
    quantity    INT NOT NULL,
    capacity    INT NOT NULL,
    PRIMARY KEY (machine_id, slot_id)
)

TABLE cash_float (
    machine_id   VARCHAR,
    denomination VARCHAR,
    count        INT NOT NULL,
    PRIMARY KEY (machine_id, denomination)
)
```
Writing `slot_state`/`cash_float` would need to happen in the **same
transaction** as recording the sale (an actual ACID transaction at the
`SERIALIZABLE` or at least `REPEATABLE READ` isolation level, or an
application-level write-ahead-log replayed on restart) — otherwise a crash
between "dispense recorded" and "inventory count persisted" reintroduces
exactly the kind of inconsistency the in-memory version avoids by having
both live in the same process and the same lock scope. This is explicitly
called out as a production concern, not solved here, per §17.

---

## 13. Failure Scenarios

| Scenario | Behavior | Where handled |
|---|---|---|
| Customer selects an empty slot | `SlotOutOfStockException`, no state change, customer can pick another slot or cancel | `HasMoneyState.selectProductForCash`, `IdleState.payByCard` |
| Inserted cash < price | `InsufficientFundsException`, no state change, balance preserved, customer can insert more | `CashPaymentStrategy.settle` |
| Exact change impossible | `InsufficientChangeException`; the just-deposited cash is reversed out of the box (`CashBox.reverseDeposit`), the customer's exact inserted cash is refunded, state returns to `Idle` | `CashPaymentStrategy.settle` (reversal) + `HasMoneyState.selectProductForCash` (refund + transition) |
| Card gateway declines/times out | `PaymentDeclinedException`; nothing was ever deposited or dispensed (card is charged *before* `dispenseOne()`), state stays `Idle` | `CardPaymentStrategy.settle`, `IdleState.payByCard` |
| Entire machine depletes mid-session | After the dispense that empties the last slot, the very next state is `OutOfStockState`; no further purchases accepted until restock | `HasMoneyState`/`IdleState` post-dispense check: `inventory.isCompletelyEmpty()` |
| Admin tries to restock a nonexistent slot | `ProductNotFoundException` from `Inventory.getSlot` | `Inventory.getSlot` |
| Admin tries to restock beyond capacity | `IllegalArgumentException`, no mutation | `Slot.restock` |
| Operator tries to enter maintenance mid-transaction | `InvalidStateTransitionException` — every state but `Idle`/`OutOfStock` rejects `enterMaintenance` | `VendingMachineState` default method |
| Duplicate/double dispense attempt on the last unit (race) | Exactly one caller's `dispenseOne()` succeeds; all others see `SlotOutOfStockException` from the *same* `synchronized` check-then-act | `Slot.dispenseOne` (demonstrated in `VendingMachineTest`'s concurrency test) |
| Mid-dispense crash (process dies after payment settled, before `dispenseAndLog`/state transition) | **Known gap in the interview-scope solution**, explicitly named rather than hidden: in-memory state is lost, so on restart the machine would not know a sale was "in flight." Production fix (§17): persist "payment settled, dispense pending" as a durable, idempotent step *before* attempting the physical dispense, so a restart can detect and complete (or compensate) an interrupted dispense. |
| Resource exhaustion: cash box completely full (can't accept more physical coins) | Not modeled in this interview scope (a real coin acceptor has a finite tube capacity); documented as a production hardware-interlock concern (§17), not an application-layer invariant here, since the scope guidance did not ask for accept-side capacity limits. |
| Retry of a card charge after an ambiguous network timeout | **Not naively retried** — a real gateway integration must use an idempotency key per transaction attempt so a retried charge after a timeout cannot double-charge the customer; `MockCardPaymentGateway` is a stand-in and intentionally does not model this, called out explicitly in its Javadoc and in §17. |

---

## 14. Performance and Scalability

**Bottlenecks and fixes, single-process (this repo's scope):**
- The only real contention point is `machineLock` serializing front-panel
  operations — and as argued in §11, that is *not* a bottleneck to fix,
  because it mirrors physical reality (one session at a time). "Fixing" it
  with finer-grained locking would add complexity for zero real throughput
  gain.
- `CashBox`'s lock is held only for the duration of a deposit/withdraw/
  collect call (microseconds of EnumMap arithmetic) — not a realistic
  contention source even under the admin-console-vs-customer scenario in
  §11, because those operations are rare and fast.
- `Inventory`'s `ConcurrentHashMap` and `isCompletelyEmpty()`'s O(slots) scan
  are both fine at realistic scale (tens of slots); this would only need
  revisiting if a single machine had thousands of slots, which does not
  describe any real vending machine.

**Multi-threaded extensions (still single machine, e.g. async hardware
drivers):** the lock-splitting already in place (`machineLock` vs.
`Slot`-level vs. `CashBox`-level) is exactly the structure you'd keep if
sensor callbacks moved onto their own threads — no redesign needed, just
dispatching those callbacks to call into the existing, already-thread-safe
methods.

**What NOT to do: do not distribute a single vending machine.** The scope
guidance explicitly warns against over-distributing an LLD problem, and this
is the clearest possible example: one machine does not need microservices,
a message queue, or a distributed cache — it needs correct in-process
locking, which it has. If the real requirement is a *fleet* of machines
reporting to a shared backend, that backend is a different system with a
different design (sharded inventory-per-machine-id, an event stream for
sales, eventual consistency between machines and a central dashboard) —
named here as the explicit boundary of this design's responsibility, not
solved by stretching `VendingMachine` to do both jobs.

---

## 15. Testing Strategy

Tests live under `tests/lld/vendingmachine/`, mirroring the `src/` package
structure, written in JUnit 5.

| Test class | Covers |
|---|---|
| `domain/MoneyTest` | Value-object correctness: rounding, negative rejection, comparison, formatting (unit, boundary) |
| `domain/CashBoxTest` | Deposit/withdraw/reverse round-trips, atomic failure (no partial mutation), admin collection math, **concurrent withdrawal race** (concurrency) |
| `change/GreedyChangeMakerTest` | Ample-float breakdown, scarce-denomination fallback, infeasible-amount failure, zero-amount edge case (unit, boundary, negative) |
| `payment/CardPaymentStrategyTest` | Successful charge returns an auth reference with no change; decline propagates without mutating state (unit, negative) |
| `core/VendingMachineTest` | Full state-machine integration: cash purchase + change, insufficient funds (recoverable), insufficient change (abort + refund), card purchase, card decline, cancel/refund, per-slot stock-out, whole-machine `OutOfStock` transition, admin restock recovery, maintenance-mode blocking and guarding against mid-transaction entry, **and a concurrency race for the last unit of a slot** (integration, failure-injection, concurrency) |

**Representative test (negative/failure-injection), from `VendingMachineTest`:**
```java
@Test
void insufficientChangeAbortsAndRefundsExactInsertedCash() {
    CashBox noSmallChangeBox = new CashBox();
    noSmallChangeBox.load(Denomination.ONE_DOLLAR, 10); // only $1 bills: cannot make $0.75 change

    VendingMachine strictMachine = new VendingMachine(
            inventoryWithOneDollarTwentyFiveCentItem, noSmallChangeBox,
            new GreedyChangeMaker(), new MockCardPaymentGateway());

    strictMachine.insertCoin(Denomination.ONE_DOLLAR);
    strictMachine.insertCoin(Denomination.ONE_DOLLAR); // balance $2.00, price $1.25, owed $0.75 change

    assertThrows(InsufficientChangeException.class, () -> strictMachine.selectProductForCash("A1"));

    assertEquals("IDLE", strictMachine.getCurrentStateName());         // aborted cleanly
    assertEquals(Money.ZERO, strictMachine.getCurrentBalance());       // refunded
    assertEquals(2, inventoryWithOneDollarTwentyFiveCentItem.getSlot("A1").getQuantity()); // nothing dispensed
    assertEquals(Money.ofCents(1000), noSmallChangeBox.totalValue());  // box kept nothing extra
}
```

**Representative concurrency test, from `VendingMachineTest`:**
```java
@Test
void concurrentCardPurchaseAttemptsOnLastUnitDispenseExactlyOnce() throws InterruptedException {
    // 20 threads race machine.payByCard("A2") against a single-unit slot.
    // Asserts: exactly 1 success, 19 SlotOutOfStockException, final quantity == 0,
    // exactly 1 transaction recorded.
}
```

**Verified run (JUnit 5 Platform Console Launcher, this machine):**
```
29 tests found, 29 successful, 0 failed, 0 skipped
```
See §"Final Verification" at the end of this document for the full command
and output.

---

## 16. Trade-offs and Alternatives

| Decision | Chosen approach | Alternative | Why chosen | When the alternative wins |
|---|---|---|---|---|
| Money representation | Integral cents (`long`) | `BigDecimal` | Simpler, faster, exactly correct for a fixed-minor-unit currency | Multi-currency / arbitrary-precision production system — `BigDecimal` wins there |
| Locking granularity | Coarse `machineLock` for front panel, fine-grained for `Slot`/`CashBox` | One single lock for everything | Matches physical reality (one session) while still letting admin ops run concurrently with customer ops | If the "machine" were actually a multi-head/multi-session kiosk (e.g. two coin slots sharing one inventory), coarse front-panel locking would need to become per-session instead |
| Change algorithm | Greedy | Dynamic programming (bounded coin-change) | O(d) vs O(amount×d); correct and optimal for this canonical denomination set | Non-canonical/configurable denominations — DP is the only generally-correct choice there |
| Payment method selection | Caller picks cash-flow (`selectProductForCash`) vs card-flow (`payByCard`) explicitly, no mixing mid-transaction | A unified `pay(slotId, PaymentMethod)` entry point that internally branches | Keeps `HasMoneyState`'s cash-specific invariants (insert-then-select, refund-on-cancel) from leaking into a card flow that has none of them | If mixed-method payment became a real requirement, a unified ledger-based payment object would be needed instead of two independent strategies |
| Whole-machine `OutOfStock` as a top-level state vs. a derived property | Explicit state class, checked and transitioned to after every dispense | Compute `isCompletelyEmpty()` on demand inside `IdleState` instead of maintaining a separate state | The assignment explicitly lists OutOfStock as a top-level machine state alongside Idle/HasMoney/Dispensing/Maintenance, and making it explicit documents "no new transaction may start" as a first-class, inspectable fact (`getCurrentStateName()`) rather than a side computation | If OutOfStock were purely informational (UI hint only, transactions still allowed to attempt and fail per-slot), the derived-property approach would be simpler and sufficient |
| In-memory only, no DB | Chosen | Durable relational schema per §12's sketch | Matches stated scope; avoids speculative infrastructure | Production deployment needing crash-recoverable sales/cash records — see §17 |
| Transaction log structure | `CopyOnWriteArrayList` | `ConcurrentLinkedQueue` or a bounded ring buffer | Admin reads (iterate whole history) are expected to be more performance-sensitive than the already-rare writes; a ring buffer would need an eviction policy decision that isn't asked for here | A very high-sale-volume machine (unrealistic for a physical vending machine, but imaginable for a very busy one) might prefer a bounded structure to cap memory |

---

## 17. Production Evolution

Kept explicitly separate from the core interview solution above.

- **Monitoring/metrics.** Emit counters for: successful sales by payment
  method, `InsufficientChangeException` rate (a leading indicator the float
  needs topping up before customers are turned away), `PaymentDeclinedException`
  rate, time spent in `DispensingState` (hardware health signal), and
  `OutOfStockState` dwell time per slot (restock SLA tracking).
- **Logging.** Structured, correlation-id-tagged logs per transaction
  attempt (not just successes) so a support engineer can reconstruct "what
  happened" for a specific customer complaint ("the machine took my money
  and didn't dispense").
- **Durable persistence.** As sketched in §12: a local embedded durable store
  (e.g. SQLite or an append-only log file) so inventory counts, cash float,
  and transaction history survive a process restart or power loss, with
  recovery logic that reconciles "payment settled but dispense not
  confirmed" states (the gap named in §13).
- **Distributed coordination (only if genuinely a fleet).** If many physical
  machines report to a shared backend (central inventory dashboard, remote
  restock-request queue), that backend needs its own concurrency story:
  optimistic concurrency control (version columns) per machine-id/slot-id row,
  and an event-sourced or outbox-pattern write path so "machine reported a
  sale" and "central inventory decremented" cannot diverge under a partial
  network failure. This is a different system from the one in this repo, not
  an extension of `VendingMachine` itself.
- **Fault tolerance for payment.** A real card gateway integration needs
  idempotency keys per charge attempt, bounded retries with backoff on
  ambiguous timeouts, and a reconciliation job that cross-checks "gateway
  says charged" against "machine says dispensed" nightly.
- **Operational concerns.** Remote firmware/software update channel, a
  documented maintenance-mode audit trail (who entered it, when, why),
  alerting when a machine has been in `OutOfStockState` beyond a threshold,
  and a kill-switch to force-disable card payments if the gateway is
  degraded (fail toward cash-only rather than fully down).
- **Security.** `AdminService` in this repo has no authentication — in
  production, every admin operation needs an authenticated/authorized
  operator identity and an audit log entry, which is why `AdminService` is
  already a separate class from `VendingMachine`: it's the natural seam to
  add an auth check without touching customer-facing code.

---

## Final Verification (this session)

Compiled and ran the full test suite with the JUnit 5 Platform Console
Launcher standalone jar (no network access required):

```
javac -d build/out -cp junit-platform-console-standalone.jar $(find src tests -name '*.java')
java -jar junit-platform-console-standalone.jar --class-path build/out --scan-class-path
```

Result: **8 containers, 29 tests found, 29 successful, 0 failed, 0 skipped.**
The `javac` compile of `src/` alone also succeeds standalone (no JUnit
dependency needed) with only benign `serialVersionUID` warnings on the
custom exception classes.
