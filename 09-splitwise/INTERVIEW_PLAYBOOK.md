# Interview Playbook — Splitwise (Expense Sharing System)

A speakable, timed script for a 30/45/60-minute LLD round on this problem.

---

## 1. How to open the discussion

Don't start coding. Start by restating the problem in your own words and
proposing scope out loud:

> "So we're designing something like Splitwise — users can log shared
> expenses in a group, split them a few different ways, and the system keeps
> track of who owes whom. I'd like to spend the first few minutes nailing
> down scope and requirements, then move to the domain model, then the
> trickier parts — money precision, the ledger, concurrency, and debt
> simplification — and leave time at the end for testing and trade-offs.
> Does that sound like a reasonable plan?"

This signals structure and time-awareness immediately, which staff-level
interviewers specifically look for.

## 2. First 5 clarifying questions to ask

1. "Is this single-currency, or do we need multi-currency support?" (drives
   whether `Money` needs a currency field)
2. "Can expenses be edited/deleted after creation, or are they append-only?"
   (drives mutability design)
3. "What split types do we need — equal, exact amounts, percentages? Anything
   else, like splitting by shares/weights?"
4. "When we simplify debts, do we need to actually execute the new payment
   plan, or just suggest it to the user?"
5. "Do we need the mathematically minimum number of settlement transactions,
   or is a small/reasonable number good enough?"

## 3. Requirements to establish

State these back explicitly once answered (or assumed): single currency;
expenses are immutable facts; 3 split strategies (equal/exact/percent);
debt simplification is a suggestion, not auto-executed; near-minimal
transaction count is fine (not provably optimal); concurrent expense
submission must be safe.

## 4. How to identify core entities

Walk it top-down from the problem statement nouns, then refine:
> "The obvious nouns are User, Group, Expense. An Expense needs a split
> mechanism, so I'll pull that out as its own concept rather than cramming
> logic into Expense itself. And critically, 'who owes whom' isn't naturally
> a field *on* Expense or User — it's a derived, aggregated view across many
> expenses, so I want a dedicated Ledger/BalanceSheet concept that's
> separate from the raw expense history."

This last sentence is the single most important modelling insight in this
problem — say it early.

## 5. How to explain class relationships

> "A Group has many Users as members — by id, not object reference, so
> membership changes don't require touching Expense objects. An Expense
> belongs to one Group (or none, for a direct expense) and owns a list of
> ExpenseShares — one per participant — which must always sum exactly to
> the expense total. The BalanceSheet doesn't reference Expense objects at
> all; it just accumulates the shares as they're applied, so it stays
   decoupled from the history and only cares about current state."

## 6. How to draw the architecture

Draw three layers left-to-right or top-to-bottom on the whiteboard:
**Services** (UserService/GroupService/ExpenseService/SettlementService) →
**Domain logic** (SplitStrategy implementations, DebtSimplifier) →
**Ledger** (BalanceSheetService owning per-group BalanceSheets) with
**Repositories** underneath everything. Say explicitly: "Services never talk
to the ledger's internals directly — BalanceSheetService is the only thing
that knows about locking."

## 7. Which interfaces to create first

1. `Money` (as a class, not strictly an "interface", but it's the first
   thing to nail down — everything else depends on getting this right).
2. `SplitStrategy` (interface) + `SplitInput` (sealed input hierarchy).
3. Repository interfaces (`UserRepository`, etc.) — even if you only ever
   write the in-memory implementation, declaring the interface first signals
   you're thinking about swappability.

## 8. Which classes to implement first

In order: `Money` → `User`/`Group` (trivial) → `ExpenseShare`/`Expense`
(with the exact-reconciliation invariant in the constructor) →
`EqualSplitStrategy` (simplest strategy, demonstrates the remainder-
distribution technique) → `BalanceSheet` (the ledger) → `ExpenseService`
(wires strategy + ledger together) → `DebtSimplifier` (only if time remains
— see §13 below for what to cut first).

## 9. Which design patterns to introduce and when

Introduce **Strategy** the moment you mention "equal vs exact vs percent" —
don't wait to be asked. Introduce the (simple, static) **Factory** right
after, to map `SplitType` to a strategy instance. Mention **Repository**
briefly when you set up persistence, but don't over-invest time drawing it —
it's expected, not impressive. Explicitly say why you're **not** using
Singleton for the ledger (testability) — this pre-empts a common staff-level
follow-up.

## 10. How to discuss concurrency

Don't wait for the interviewer to ask "what about thread safety?" — raise it
yourself once the `BalanceSheet` is on the board:

> "One thing I want to flag: a single expense touches multiple balance
> entries at once — the payer against every other participant. That's a
> multi-key read-modify-write, which a ConcurrentHashMap alone doesn't make
> atomic. So I'm going to guard each group's balance sheet with its own
> read-write lock: writers (expense/settlement application) take the write
> lock for the whole operation, readers (balance queries) take the read
> lock. I'm deliberately choosing one lock *per group* rather than per user-
> pair, because per-pair locking would need a strict lock-ordering protocol
> across pairs to avoid deadlock, for not much real parallelism gain within
> one group."

## 11. How to discuss edge cases

Have these ready, rehearsed: (a) splitting an amount that doesn't divide
evenly — remainder distribution; (b) percentages that don't sum to exactly
100 due to floating input like 33.33×3; (c) settling a debt that doesn't
exist; (d) a user who isn't a group member being charged; (e) retried expense
submissions (idempotency); (f) the payer being one of the split participants
(no self-debt).

## 12. How to handle interviewer interruptions

If interrupted with "what if two people add expenses at the same time?"
mid-way through modelling — don't panic or go back to the whiteboard from
scratch. Say: "Good, let me note that and come back to it once the ledger
shape is settled, since the answer depends on how I structure balance
storage" — then actually come back to it. If pushed to go deeper
immediately, it's fine to pivot: concurrency is core to this problem anyway.

## 13. How to prioritise if time is running out

Cut, in this order, if short on time: (1) multi-currency / production
evolution discussion — skip entirely, mention briefly at the end; (2) the
PERCENT split's largest-remainder apportionment detail — implement EQUAL and
EXACT fully, describe PERCENT verbally; (3) the full correctness proof of
debt simplification — implement the greedy algorithm, state its complexity
and the "not globally optimal" caveat verbally without the counterexample;
(4) idempotency — mention it as a one-liner "I'd add an idempotency key
param here" without implementing. **Never cut:** the money-precision
discussion, the core ledger locking rationale, and the Strategy pattern for
splits — these are the three things this problem is specifically testing.

## 14. Three full walkthroughs with realistic timelines

### 30-minute walkthrough
- 0-5 min: scope + clarifying questions (see §2).
- 5-10 min: entities (User/Group/Expense/ExpenseShare/Money) + why Money is
  integer minor units, not double.
- 10-18 min: Strategy pattern for splits; implement `EqualSplitStrategy` with
  remainder distribution live; describe Exact/Percent verbally.
- 18-25 min: BalanceSheet design + locking rationale (verbal + key method
  signatures, not full implementation).
- 25-30 min: mention debt simplification exists and its complexity/
  correctness bound verbally; wrap with trade-offs if time allows.

### 45-minute walkthrough
- 0-5 min: scope + clarifying questions.
- 5-12 min: full domain model + diagram on the board.
- 12-22 min: implement `Money`, `Expense`, `EqualSplitStrategy`,
  `ExactSplitStrategy` with working code.
- 22-32 min: implement `BalanceSheet` with the read-write lock, explain the
  granularity decision out loud as you write it.
- 32-40 min: implement `DebtSimplifier`'s greedy loop; state its complexity
  and the sub-optimality caveat with a quick example.
- 40-45 min: testing strategy + 1-2 trade-offs discussion.

### 60-minute walkthrough
- 0-5 min: scope + clarifying questions.
- 5-12 min: full domain model + architecture diagram.
- 12-25 min: `Money`, `Expense`/`ExpenseShare`, all three `SplitStrategy`
  implementations (including the largest-remainder method for percentages).
- 25-38 min: `BalanceSheet` + `BalanceSheetService`, full locking rationale,
  a quick sketch of the concurrent-expense-addition sequence diagram.
- 38-48 min: `DebtSimplifier` with full correctness reasoning and the
  NP-hard-exact-optimum caveat, ideally with the counterexample.
- 48-55 min: idempotency for `ExpenseService.addExpense`; failure scenarios.
- 55-60 min: testing strategy, trade-offs, and a brief production-evolution
  gesture (persistence, monitoring) to show awareness without over-building it.

## What I would say to the interviewer

- "Before I write any code, let me just double-check scope — are we single
  currency here? That changes how I represent money."
- "I'm going to model money as a long count of cents rather than a double,
  because floating point can't represent most decimal fractions exactly, and
  this system's whole job is making sure the numbers always add up exactly."
- "I want to pull the splitting logic out into its own Strategy interface,
  because equal/exact/percent splits each have their own validation and
  rounding edge cases, and I don't want ExpenseService to become a dumping
  ground for all of that."
- "Here's the subtle bit: an expense can touch several balance pairs at
  once, so I need that whole update to be atomic, not just individually
  atomic per pair — that's why I'm reaching for an explicit lock here,
  not just a concurrent map."
- "I'm deliberately locking per-group rather than per-user-pair, because
  per-pair locking would force me to acquire multiple locks in a consistent
  order to avoid deadlock, for not much real benefit given expenses usually
  touch most of a group's pairs anyway."
- "This debt-simplification algorithm is a well-known greedy approach — two
  max-heaps, pair off the biggest creditor and biggest debtor each time. It's
  not guaranteed to find the mathematically fewest transactions possible —
  that's actually NP-hard in general — but it's polynomial and gets close,
  which is the right trade-off for a real product."
- "If this had to go to production, the main things I'd add are a real
  database behind these repositories, metrics on lock contention and
  settlement counts, and probably sharding the ledger by group id since
  groups are already naturally independent."
- "I'll leave multi-currency and payment-gateway integration out of scope
  unless you want me to go there — happy to discuss the shape of that change
  if useful."
