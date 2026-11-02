# Implementation Guide — Splitwise (Expense Sharing System)

A practical, whiteboard/live-coding-oriented guide to actually *writing* this
system under interview conditions — as opposed to `README.md` (the finished
design) or `INTERVIEW_PLAYBOOK.md` (how to run the conversation). This
answers: in what order do I write classes, what do I say while I type, what
mistakes do candidates actually make here, and what do I cut if I'm behind?

---

## 1. Which classes to write first

Write in **dependency order**, bottom-up, so every class you write compiles
against things that already exist — never leave a class referencing a type
you haven't written yet for more than a minute or two.

1. **`Money`** — everything else depends on it; get the precision decision
   (minor units, not `double`) stated and typed out before touching anything
   else, since reversing it later touches every signature.
2. **`User`, `Group`** — trivial value/entity types, no business logic yet.
3. **`SplitType` (enum), `SplitInput` (sealed interface + 3 records), `ExpenseShare`**
   — the data shapes for splitting, before the logic that consumes them.
4. **`SplitStrategy` (interface) + `EqualSplitStrategy`** — get one concrete
   strategy compiling and testable before writing the other two; this is
   your first real correctness logic and your first unit test.
5. **`ExactSplitStrategy`, `PercentSplitStrategy`, `SplitStrategyFactory`**
   — now that the shape is proven with Equal, the other two are mostly
   repetition of the same pattern.
6. **`Expense`** — now that `ExpenseShare`/splitting exist, the aggregate
   that owns them (with its sum-must-equal-total invariant) makes sense.
7. **`UnorderedUserPair`, `BalanceSheet`** — the ledger; this is where you
   narrate locking decisions out loud (see §7 below).
8. **`BalanceSheetService`** — the thin translation layer from domain events
   to ledger calls.
9. **`DebtSimplifier`** — the algorithmic centerpiece; save it for after the
   ledger exists, since it consumes `computeNetBalancePerUser`'s output.
10. **`Settlement`, `SettlementService`** — settle-up plus the simplification
    entry point.
11. **Repositories and `*Service` orchestration classes last** — they're the
    least interesting part structurally (CRUD + idempotency plumbing) and
    the safest thing to abbreviate if you're low on time (see §8).

---

## 2. Minimal compilable implementation outline

If you need *something on the board compiling* in the first 10 minutes,
this is the smallest skeleton that's still structurally honest (not a toy
you'll have to throw away):

```java
// Money: the one decision that must be right before anything else is written.
public record Money(long minorUnits) {
    public static Money ofMinorUnits(long units) { return new Money(units); }
    public Money add(Money other) { return new Money(minorUnits + other.minorUnits); }
    public boolean isZero() { return minorUnits == 0; }
}

// A single split strategy, to prove the interface shape before writing the other two.
public interface SplitStrategy {
    List<ExpenseShare> computeShares(Money totalAmount, SplitInput input);
}

public record ExpenseShare(String userId, Money amount) {}

// The ledger: start with the map and the lock, add methods as you need them.
public final class BalanceSheet {
    private final Map<UnorderedUserPair, Long> balances = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public void recordDebt(String debtor, String creditor, Money amount) {
        lock.writeLock().lock();
        try {
            // ... canonicalize pair, add signed delta, remove entry if zero.
        } finally {
            lock.writeLock().unlock();
        }
    }
}
```

This alone is enough to demonstrate: an exact-money decision, the Strategy
interface shape, and the lock-guarded ledger — the three things an
interviewer is most likely to ask about in the first half of the session.

---

## 3. Suggested implementation sequence (with rough time budget, 45-min session)

| Time | What you're writing | Why now |
|---|---|---|
| 0–5 min | Talk through requirements/entities (no code yet) | Never start typing before the shape is agreed — see `INTERVIEW_PLAYBOOK.md` |
| 5–10 min | `Money`, `User`, `Group` | Foundational, low-risk, shows you move fast on the "boring" parts |
| 10–18 min | `SplitInput`, `ExpenseShare`, `SplitStrategy` + `EqualSplitStrategy` | Core pattern #1 (Strategy); narrate the Open/Closed story here |
| 18–22 min | `ExactSplitStrategy`, `PercentSplitStrategy` (abbreviated if short on time) | Proves the pattern generalizes; percent rounding is a good "I know the edge case" moment |
| 22–32 min | `UnorderedUserPair`, `BalanceSheet` (lock + `recordDebt` + `getBalance`) | The concurrency centerpiece — spend real time narrating lock granularity here |
| 32–40 min | `DebtSimplifier` (two-heap greedy) | The DSA centerpiece; state the complexity and the "not globally optimal" caveat explicitly, don't wait to be asked |
| 40–45 min | Wire up `ExpenseService`/`SettlementService` at a sketch level (signatures, not full bodies) + recap | Shows you know how the pieces connect even if you don't finish every method body |

---

## 4. Important methods (the ones worth getting exactly right)

- **`SplitStrategy.computeShares`** (all three implementations) — must
  guarantee shares sum to *exactly* the total, every time, with no epsilon
  tolerance. This is the single most important correctness property in the
  whole system; say so out loud.
- **`PercentSplitStrategy`'s rounding step** — compute each share by
  truncating/flooring, then distribute the leftover minor units one-by-one
  to the shares with the largest fractional remainder (the "largest
  remainder method") so the sum is exact without any single share being
  arbitrarily over/under-rounded.
- **`BalanceSheet.applyExpenseShares`** (or your equivalent single-lock
  batch-apply method) — must acquire the write lock **once** for an entire
  multi-participant expense, not once per participant; this is the exact
  detail that separates "per-pair atomic" from "per-expense atomic," and
  it's a very easy thing to get subtly wrong by looping a single-pair method
  from outside the lock.
- **`BalanceSheet.trySettle`** (atomic check-and-reduce) — must validate the
  debt still exists *and* apply the reduction under one critical section;
  splitting "check" and "act" into two separate lock acquisitions is the
  single most common concurrency bug an interviewer will probe for in this
  problem.
- **`DebtSimplifier.simplify`** — the two-max-heap greedy loop; know cold
  that it terminates in at most `n-1` transactions, runs in `O(n log n)`,
  and is provably not globally optimal (have the counterexample ready — see
  `STAFF_LEVEL_DEEP_DIVE.md` Q9 and `README.md` §10).

---

## 5. Common coding mistakes (specific to this problem)

1. **Using `double` for money "because it's simpler to demo."** This is the
   single most commonly flagged mistake in this problem — state the
   decision (minor units or `BigDecimal`) before writing a single method
   signature, not as an afterthought.
2. **Checking `shares.sum() == total` with a tolerance/epsilon** instead of
   exact equality. With integer minor units, exact equality is not just
   possible but required — any epsilon here silently reintroduces the
   floating-point problem you just solved by switching representations.
3. **Looping a single-pair lock-acquiring method to apply a multi-participant
   expense** (e.g. calling `recordDebt` once per participant from outside
   any lock) — this looks correct (each call is "thread-safe") but leaves a
   torn-read window across the whole expense. Always ask: "what's the unit
   of atomicity the business actually needs?" before deciding where the
   lock boundary goes.
4. **Forgetting the payer can also be a participant.** An equal split across
   the group including the payer means the payer owes themselves their own
   share, which must net to zero/be skipped — a surprisingly common
   off-by-one-participant bug.
5. **Treating debt-simplification as a bug-fix instead of a trade-off.**
   Candidates sometimes try to "fix" the greedy algorithm's
   non-optimality mid-interview instead of stating it as a known,
   deliberate time/optimality trade-off (polynomial greedy vs. NP-hard
   exact). State the trade-off; don't apologize for it.
6. **Mutating `Expense` after construction** (e.g. adding an `addShare`
   method) instead of treating it as an immutable fact — breaks the
   sum-equals-total invariant's guarantee and makes event-replay-based
   recovery unsound.
7. **Making `BalanceSheet` one global lock "to keep it simple."** This
   compiles and passes small tests, but it's a scalability answer that
   falls apart the moment you're asked "what if there are 10,000
   independent groups?" — know the per-context granularity argument before
   you're asked, not after.

---

## 6. Edge cases to call out proactively (don't wait to be asked)

- An expense where the payer is **not** a participant at all (e.g. "Alice
  treats the group, pays nothing herself").
- A group of exactly 2 people (debt simplification is trivially a no-op —
  there's only one possible pairwise balance already).
- Percent splits that don't sum to exactly 100% due to floating client
  input (e.g. `33.333...% × 3`) — state the rounding/reconciliation policy.
- A settlement for more than what's actually owed (overpayment) — decide
  explicitly whether this is allowed (reverses the debt direction) or
  rejected, and say which you chose and why.
- Settling a debt that doesn't exist at all, or exists in the *opposite*
  direction — must be rejected, not silently create a negative-then-positive
  flip.
- A group with zero expenses (simplification/balances must return empty,
  not throw).
- Two users with a non-zero balance who are never in any group together
  (direct/non-group expenses) — make sure your context-id scheme (e.g. a
  sentinel "direct" context) actually covers this.

---

## 7. How to narrate the code while writing it

Say the decision **before** the keystroke, not after — an interviewer
watching you type `long minorUnits` with zero commentary learns nothing
about your reasoning, even if the code is correct. A good rhythm:

- *"I'm representing money as integer minor units rather than `double`,
  because floating point can't represent values like 0.1 exactly, and
  repeated addition/subtraction across many expenses would accumulate
  visible drift — pennies disappearing or appearing over time. I'll keep
  `BigDecimal` only at the edges, for parsing/display."* (then write `Money`)
- *"I'm making `SplitStrategy` an interface with one method, because I know
  I need at least three implementations and I don't want `ExpenseService`
  to know which one it's using."* (then write the interface + one impl)
- *"I'm putting one `ReentrantReadWriteLock` per group's ledger, not a
  global lock and not a lock per user-pair — a global lock would serialize
  unrelated groups for no reason, and a per-pair lock needs a strict
  acquisition ordering to avoid deadlock across overlapping multi-party
  expenses, which is complexity I don't think pays for itself here."*
  (then write `BalanceSheet`)
- When you hit the debt-simplification algorithm: *"I'm going to use a
  greedy max-heap approach — it's polynomial and simple to implement
  correctly live, but I want to say up front that it is **not** always the
  globally minimal number of transactions; the true optimum is NP-hard. I
  have a concrete 6-person counterexample if you want to see where it
  falls short."* — stating the limitation unprompted reads as far more
  senior than waiting to be caught.

---

## 8. What can be safely omitted under time pressure

Safe to cut (say what you'd do, don't implement it):
- Full repository implementations beyond one in-memory example (e.g. fully
  implement `InMemoryExpenseRepository`, just describe the others as
  "identical shape").
- `PercentSplitStrategy`'s largest-remainder rounding detail — implement
  simple truncation first, mention the exact-reconciliation refinement
  verbally if time allows.
- Exhaustive custom exception hierarchy — two or three exception types
  (invalid split, not found, invalid settlement) are enough to demonstrate
  the pattern; you don't need one exception class per failure mode.
- Idempotency-key handling on `addExpense` — mention it as a required
  production concern, implement it only if time remains after the core
  ledger + algorithm are done.
- A fully wired `*Service` orchestration layer with every CRUD method —
  sketch the two or three methods that matter (`addExpense`,
  `recordSettlement`, `suggestSimplifiedSettlements`) and name the rest.

Never cut, even under severe time pressure:
- The money-precision decision and why.
- At least one working `SplitStrategy` with a real correctness check
  (shares sum to total).
- The ledger's lock and an explicit statement of what atomicity unit it
  protects.
- A stated (even if not fully coded) debt-simplification approach with its
  complexity and optimality caveat.

---

## 9. A simplified version implementable in 20–30 minutes

If explicitly asked for a stripped-down version (or you realize you're
critically short on time), build exactly this and say so explicitly up
front — *"given the time, I'm going to build a simplified version: two
users only /no groups, one split type, no persistence abstraction"*:

1. `Money` (minor units, `add`/`subtract`/`isZero` only).
2. `User` (id + name, nothing else).
3. One `SplitStrategy`: `EqualSplitStrategy` only (skip `Exact`/`Percent`
   entirely, state they'd follow the same interface).
4. `ExpenseShare`, `Expense` (skip `Group` — assume all expenses are
   "direct," i.e. no group context at all).
5. `BalanceSheet` with `recordDebt`/`getBalance` only (skip
   `computeNetBalancePerUser`/`getBalancesForUser` — not needed without
   multi-party simplification).
6. A single `ExpenseService.addExpense(payerId, totalAmount, List<String>
   participantIds)` method that computes equal shares and calls
   `BalanceSheet.recordDebt` once per participant directly (acceptable to
   skip the single-lock batch-apply refinement here *if you say explicitly*
   that you're skipping it for time and name the torn-read risk it
   reintroduces).
7. Skip entirely: debt simplification, settlements, repositories,
   idempotency, groups. State clearly: *"If I had another 20 minutes, next
   I'd add groups, settle-up, and the debt-simplification algorithm, in that
   order — simplification needs the net-balance view, which needs groups to
   be meaningful across more than 2 people."*

This simplified version still demonstrates every *technique* the full
design uses (exact-money representation, Strategy, a locked ledger) at a
fraction of the surface area — which is exactly what a time-boxed interview
is actually testing.
