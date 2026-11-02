# Splitwise (Expense Sharing System) — Low-Level Design

> Staff/Principal-level backend interview prep. Audience: experienced Java engineers
> preparing for Staff-level LLD rounds. Package: `lld.splitwise`. Java 17+.

## Table of Contents
1. [Problem Understanding](#1-problem-understanding)
2. [Requirement Gathering](#2-requirement-gathering)
3. [Clarifying Questions](#3-clarifying-questions)
4. [Domain Modelling](#4-domain-modelling)
5. [Domain Invariants](#5-domain-invariants)
6. [Architecture and Class Diagram](#6-architecture-and-class-diagram)
7. [Design Patterns](#7-design-patterns)
8. [API and Method Design](#8-api-and-method-design)
9. [Core Implementation](#9-core-implementation)
10. [Data Structures and Algorithms](#10-data-structures-and-algorithms)
11. [Concurrency and Thread Safety](#11-concurrency-and-thread-safety)
12. [Persistence and Database Design](#12-persistence-and-database-design)
13. [Failure Scenarios](#13-failure-scenarios)
14. [Performance and Scalability](#14-performance-and-scalability)
15. [Testing Strategy](#15-testing-strategy)
16. [Trade-offs and Alternatives](#16-trade-offs-and-alternatives)
17. [Production Evolution](#17-production-evolution)

---

## 1. Problem Understanding

**Plain explanation:** Design a system (like Splitwise, or "who-owes-who" features in
Venmo/PayPal group features) where a group of people can log shared expenses
(e.g. a dinner, a trip, a rent payment) and the system keeps track, per pair of
users, of how much each person owes the other. Users can split a bill equally,
by exact amounts, or by percentage. At any time a user can see their net
balance with any other user, and the group can "settle up" — either by paying
pairwise debts directly or by using a minimal set of consolidated payments.

**Real-world use case:** Roommates splitting rent/utilities, friends splitting
a trip's expenses, families splitting shared subscriptions. The defining
characteristic is a many-to-many "debt graph" that must always reconcile
exactly (no money appears or disappears) and must support efficient queries
("what do I owe everyone", "what does everyone owe me").

**What the interviewer is evaluating:**
- Can you correctly identify and separate the *splitting logic* (strategy
  pattern) from the *ledger/balance* concern (the hard part) from the
  *settlement/debt-simplification* concern (the "wow" part)?
- Do you reason carefully about money precision (do you reach for floating
  point, and do you catch yourself)?
- Do you think about concurrency early, not as an afterthought bolted on at
  the end?
- Do you know a real algorithm for debt simplification, and can you state its
  actual correctness/complexity properties rather than hand-waving "it just
  works"?
- Do you avoid over-engineering (e.g. do you reach for microservices/Kafka/a
  distributed ledger for what is fundamentally a modest in-memory LLD)?

**In scope:**
- Users, groups, expenses, 3 split strategies (equal/exact/percent).
- Pairwise balance sheet (ledger) per group and for direct (non-group) expenses.
- Debt simplification algorithm with full correctness/complexity analysis.
- Settle-up (recording real-world payments against the ledger).
- Thread-safety for concurrent expense submission against shared balances.
- Money/precision handling without floating-point error.

**Out of scope (explicitly, see "Production Evolution"):**
- Multi-currency / FX conversion.
- Payment gateway integration (actually moving money).
- Notifications, activity feeds, comments, receipts/photo attachments.
- Authentication/authorization, multi-tenancy, rate limiting.
- Horizontal distribution of the ledger across machines (this is single-process LLD).

---

## 2. Requirement Gathering

### Functional — Essential
1. Register users; create groups with members.
2. Add an expense with a payer, a total amount, and a split type
   (EQUAL / EXACT / PERCENT), producing per-participant shares.
3. Maintain a pairwise ledger ("who owes whom, how much") per group, and
   separately for direct (non-group) expenses between two users.
4. Query: balance between two specific users; all balances for one user;
   net balance (aggregate) for one user within a group.
5. Settle up: record that a real payment happened between two users,
   reducing/zeroing the corresponding debt.
6. Debt simplification: given a group's net balances, suggest the minimum
   (or near-minimum) set of settlement transactions to zero everyone out.

### Functional — Optional / Nice-to-have
- Editing/deleting an expense (modelled as delete+recreate to preserve an
  append-only audit trail — see Trade-offs).
- Splitting by "shares"/"weights" (e.g. 2 units vs 1 unit) — a natural
  4th `SplitStrategy`, intentionally omitted here to keep scope tight but
  trivial to add given the existing Strategy pattern.
- Multi-currency support.
- Comments/attachments/activity feed on expenses.
- Recurring expenses.

### Non-functional
| Dimension | Requirement |
|---|---|
| **Performance** | Balance queries and expense additions should be O(participants) or better; no operation should be O(total expense history) on the hot path. |
| **Scalability** | Must handle groups with hundreds of members and thousands of expenses without redesign; true horizontal scaling is an explicit non-goal for this LLD (see Production Evolution). |
| **Availability** | N/A at this scope (single JVM, in-memory); production evolution discusses replication. |
| **Consistency** | Strong consistency for the ledger: a balance read must never reflect a partially-applied expense. Every expense's shares must sum exactly to its total; the ledger's net balance must always sum to exactly zero. |
| **Extensibility** | Adding a new split type must not require changes to `ExpenseService`, `BalanceSheet`, or existing split strategies (Open/Closed). |
| **Reliability** | No expense or settlement may be silently lost or double-applied, including under retries (idempotency) and concurrent submission. |
| **Thread-safety** | Concurrent expense/settlement submission against the same group's ledger must be safe and must not corrupt balances (see §11). |

### Interview-necessary vs. production-extension split
**Interview-necessary (this repo implements all of these):** domain model,
strategy pattern for splits, exact-precision money, pairwise ledger with
documented locking, debt simplification with proof + complexity, settle-up,
idempotent expense creation, full JUnit test suite.

**Production-extension (discussed, not implemented — see §17):** persistent
storage/migrations, distributed/sharded ledger, event sourcing / CDC,
monitoring & alerting, multi-currency, payment gateway integration, API
versioning, authn/authz.

---

## 3. Clarifying Questions

These are the assumptions this implementation bakes in, stated as the
questions a candidate should ask an interviewer, with how each answer would
change the design:

1. **"Is this single-currency, or must we support multiple currencies in the
   same group?"** — This implementation assumes **single currency**. If
   multi-currency were required, `Money` would need a `Currency` field, all
   ledger arithmetic would need to reject/convert cross-currency operations,
   and debt simplification would need to run independently per currency
   (never netting a USD debt against an EUR credit without an explicit,
   auditable FX conversion step).
2. **"Can an expense be edited or deleted after creation?"** — Assumed
   **no (immutable, append-only)**; edits are modelled as delete-then-recreate
   by the caller. If true in-place editing were required, `Expense` could no
   longer be immutable, and every edit would need to compute and apply a
   *delta* to the ledger (the new shares minus the old shares) atomically,
   which is meaningfully more complex to get right under concurrency.
3. **"Do we need to support a user owing money in multiple currencies /
   across multiple groups simultaneously?"** — Assumed each group (and the
   direct/non-group context) is an independent ledger; cross-group
   aggregation is a read-time rollup (`getAggregatedBalance`), not a
   write-time concern. This avoids a single global lock across all groups.
4. **"Should we support partial settlements (I owe you $50 but I'm only
   paying $20 now)?"** — Assumed **yes**; `recordSettlement` accepts any
   positive amount and only requires that *some* debt exists in that
   direction, not that the full amount is paid at once.
5. **"When we 'simplify debts', do we actually execute the new payment plan
   automatically, or just suggest it?"** — Assumed **suggest only**:
   `suggestSimplifiedSettlements` is a pure read/compute; the caller must
   still call `recordSettlement` for each suggestion once the real-world
   payment is confirmed. This mirrors how Splitwise actually behaves (it
   shows you "pay X $20" but doesn't move money itself).
6. **"Must debt simplification find the mathematically minimal number of
   transactions, or is 'small' good enough?"** — Assumed **near-minimal is
   acceptable** (the standard greedy max-heap approach, bounded at `n-1`
   transactions, `O(n log n)`), explicitly **not** the NP-hard exact optimum.
   This is argued in depth in §10 and §16.
7. **"Is a user required to be a group member before being charged in an
   expense?"** — Assumed **yes**, strictly enforced (`NotAGroupMemberException`);
   this prevents orphaned ledger entries for users who were never properly
   part of the group's accounting context.
8. **"What should happen if two different split types are used across
   different expenses in the same group?"** — This is already naturally
   supported: `SplitType` is a per-expense property, not a per-group one; the
   ledger only ever sees the *output* (per-participant `Money` shares), so it
   is split-type-agnostic.

---

## 4. Domain Modelling

| Type | Kind | Responsibility |
|---|---|---|
| `User` | Entity (identity = `id`) | A registered person. Immutable value holder; identity-based equality. |
| `Group` | Entity (identity = `id`) | A named collection of users who share expenses. Mutable membership (`CopyOnWriteArraySet`), immutable identity/name. |
| `Money` | Value Object | Exact monetary amount in integer minor units. Immutable, no identity, structural equality. |
| `Expense` | Entity / immutable fact | A single payment event: payer, total, split type, computed shares. Append-only (never mutated after creation). |
| `ExpenseShare` | Value Object (record) | One participant's computed share of an `Expense`. |
| `Settlement` | Entity / immutable fact | A record of a real-world payment applied to reduce a debt. Append-only. |
| `SplitType` | Enum | EQUAL / EXACT / PERCENT — selects a `SplitStrategy`. |
| `SplitInput` | Value Object (sealed interface + records) | Strategy-specific parameters (participant list / exact amounts / percentages). |
| `SplitStrategy` | Strategy interface | Computes `List<ExpenseShare>` from `(Money, SplitInput)`. Three implementations. |
| `SplitStrategyFactory` | Factory | Maps `SplitType` → `SplitStrategy` singleton instance. |
| `BalanceSheet` | Domain service / aggregate | The pairwise ledger for one context (group or direct). Owns its own lock. |
| `BalanceSheetService` | Domain service | Owns all `BalanceSheet`s; translates `Expense`/`Settlement` facts into ledger mutations. |
| `DebtSimplifier` | Algorithm / stateless domain service | Pure function: net balances → minimal-ish settlement list. |
| `UserRepository` / `GroupRepository` / `ExpenseRepository` / `SettlementRepository` | Repository interfaces | Persistence abstraction; each has an in-memory implementation. |
| `UserService` / `GroupService` / `ExpenseService` / `SettlementService` | Application services | Orchestrate validation + domain logic + persistence + ledger updates. No business rules of their own beyond coordination. |

**Composition/association/ownership:**
- `Group` *has-many* `User` ids (association by id, not object reference — avoids
  stale object graphs and makes serialization trivial).
- `Expense` *owns* its `List<ExpenseShare>` (composition: shares have no
  existence or identity independent of their expense).
- `BalanceSheetService` *owns* one `BalanceSheet` per context (composition: a
  `BalanceSheet` cannot outlive/be shared outside its owning service).
- `ExpenseService`/`SettlementService` *use* (not own) repositories and
  `BalanceSheetService` — these are injected collaborators, enabling
  substitution in tests (e.g. swap in-memory repos for mocks).
- Lifecycle: `User` and `Group` are created once and live for the application's
  lifetime; `Expense`/`Settlement` are created once and never mutated
  (effectively immutable facts in an append-only log); `BalanceSheet` entries
  are the only objects that are *recomputed*/mutated in place, by design,
  since a ledger entry's whole purpose is to reflect the latest net state.

---

## 5. Domain Invariants

These must hold at all times, and this table states exactly where each is enforced:

| Invariant | Enforced where |
|---|---|
| An expense's computed shares always sum to exactly its total amount. | `Expense` constructor (defensive re-check) **and** each `SplitStrategy` implementation (by construction, via documented remainder-distribution rules). |
| A `Money` amount in an `ExpenseShare` is never negative. | `ExpenseShare` record compact constructor. |
| A `Settlement` amount is always strictly positive, and `from != to`. | `Settlement` constructor. |
| The sum of all net balances across all users in a context is always exactly zero (money is conserved — never created or destroyed). | Structurally guaranteed: every `BalanceSheet.recordDebt` call changes exactly two users' net contributions by `+delta`/`-delta`; see `BalanceSheet.computeNetBalancePerUser()` and verified in `BalanceSheetServiceTest`/`ExpenseServiceConcurrencyTest`. |
| An expense's participants (and payer) must be members of the target group, for group expenses. | `ExpenseService.addExpense` (checked before computing shares and again after, since the strategy is the source of the final participant set). |
| Percentage splits must sum to 100 (within an epsilon tolerance for benign floating input). | `PercentSplitStrategy`. |
| Exact splits must sum exactly to the expense total (exact equality — no epsilon, since `Money` is exact). | `ExactSplitStrategy`. |
| A settlement can only reduce (or reverse) debt that exists in the stated direction — you cannot "settle" a debt that doesn't exist at all. | `SettlementService.recordSettlement`. |
| Expense/settlement application to the ledger is atomic — no reader ever observes a partially-applied expense. | `BalanceSheet`'s `ReentrantReadWriteLock` (write lock held for the whole multi-entry mutation). |
| An idempotency key, once used for a successful expense creation, always resolves to the *same* expense on retry. | `ExpenseRepository.registerIdempotencyKey` (atomic `putIfAbsent`) + `ExpenseService.addExpense`. |

---

## 6. Architecture and Class Diagram

See the `diagrams/` folder (all Mermaid, generated to match the actual code in `src/`):
- [`diagrams/component-diagram.md`](diagrams/component-diagram.md) — layer/component view.
- [`diagrams/class-diagram.md`](diagrams/class-diagram.md) — full class diagram.
- [`diagrams/sequence-diagrams.md`](diagrams/sequence-diagrams.md) — add-expense,
  debt-simplification + settle-up, and concurrent-expense-addition flows.

---

## 7. Design Patterns

| Pattern | Why needed | Where applied | Alternative considered | Why rejected |
|---|---|---|---|---|
| **Strategy** | Splitting logic (equal/exact/percent) has real, independently-testable business rules (rounding, remainder distribution, validation) that vary by type and may grow (Splitwise itself later added "by shares"). Keeping it out of `ExpenseService` avoids a god class and an ever-growing `if/else`/`switch` on type scattered through the service. | `SplitStrategy` interface + `EqualSplitStrategy`/`ExactSplitStrategy`/`PercentSplitStrategy`, selected via `SplitStrategyFactory`. | A single `switch` expression inline in `ExpenseService`. | Works for 3 types, but violates Open/Closed as soon as a 4th type is added (modifies a tested, shared class), and makes each split's edge cases harder to unit test in isolation. Rejected because the Strategy version costs almost nothing extra here and pays off immediately in testability. |
| **Factory (simple static factory, not Abstract Factory)** | Centralizes "which strategy instance for which enum value" in one place instead of scattering `new EqualSplitStrategy()` calls. | `SplitStrategyFactory.forType(SplitType)`. | A full Abstract Factory / DI container / reflection-based registry (`Map<SplitType, Supplier<SplitStrategy>>` auto-registered via annotations). | Over-engineering: 3 stateless strategies never change at runtime; a static factory method is simpler, faster, and just as testable. A registry would only pay off if strategies were plugin-loaded or configured externally. |
| **Repository** | Isolates persistence details from domain/service logic; lets tests substitute in-memory implementations without mocking frameworks, and makes a future swap to a real datastore a localized change. | `UserRepository`/`GroupRepository`/`ExpenseRepository`/`SettlementRepository` interfaces + `InMemory*` implementations. | Services directly manipulating `ConcurrentHashMap`s. | Would leak storage details into business logic and make the service classes harder to test/replace; rejected because the Repository pattern costs one extra interface per entity but meaningfully improves testability and future flexibility. |
| **Value Object** (`Money`, `ExpenseShare`, `SplitInput` variants) | Money and shares have no identity — two `Money` instances with the same amount *are* the same value. Modelling them as immutable value objects with structural equality eliminates an entire class of "two amounts that look equal but aren't `.equals()`" bugs. | `Money`, `ExpenseShare` (record), `SplitInput` (sealed interface of records). | Representing money as raw `long`/`BigDecimal`/`double` passed around positionally. | Rejected: loses type safety (a raw `long` could be minor units or dollars — ambiguous at call sites), loses the ability to centralize arithmetic/rounding rules in one place, and (for `double`) reintroduces floating-point error. |
| **(Explicitly NOT used) Singleton for `BalanceSheetService`** | N/A | N/A | Making `BalanceSheetService` a classic Singleton (static instance, private constructor). | Rejected: singletons make unit testing harder (hidden global state, can't run tests in parallel with independent ledgers, can't inject a test double). Dependency injection (constructor-passed instance) achieves the "one ledger per application" goal just as well without the testability cost — each test in this repo constructs its own fresh `BalanceSheetService`. |
| **(Explicitly NOT used) Observer/Event-driven updates for the ledger** | N/A | N/A | Publishing an `ExpenseCreatedEvent` and having the ledger subscribe asynchronously. | Rejected at this scope: introduces eventual consistency (a balance query could race ahead of its triggering expense) for no benefit in a single-process LLD; this is exactly the kind of distributed-systems over-engineering the "Problem Understanding" section calls out as out of scope. Revisited in §17 Production Evolution. |

---

## 8. API and Method Design

All methods throw unchecked exceptions (see `lld.splitwise.exception`) rather
than checked exceptions — consistent with the rest of the Java standard
library's modern direction and simpler for callers in typical layered
architectures (checked exceptions on every service method become
boilerplate-generating noise at every call site).

### `UserService`
```java
User register(String name, String email);
User getOrThrow(String userId);                 // throws UserNotFoundException
void requireExists(String userId);              // throws UserNotFoundException
```

### `GroupService`
```java
Group createGroup(String name, String createdByUserId, Set<String> initialMemberIds);
Group getOrThrow(String groupId);                // throws GroupNotFoundException
void addMember(String groupId, String userId);
```

### `ExpenseService`
```java
Expense addExpense(
    String groupId,                 // nullable: null => direct (non-group) expense
    String description,
    Money amount,
    String paidByUserId,
    SplitType splitType,
    SplitInput splitInput,
    String idempotencyKey           // nullable: null => no idempotency guarantee requested
);
// throws UserNotFoundException, GroupNotFoundException, NotAGroupMemberException,
//        InvalidSplitException, DuplicateExpenseException
```
**Request/response objects:** `SplitInput` is the request-shaping sealed
interface (`EqualSplitInput`/`ExactSplitInput`/`PercentSplitInput`); `Expense`
(with its embedded `List<ExpenseShare>`) is the response. Validation
responsibility: `ExpenseService` validates existence/membership; each
`SplitStrategy` validates its own input shape and arithmetic; `Expense`'s
constructor performs a final defensive reconciliation check.

### `SettlementService`
```java
Settlement recordSettlement(String groupId, String fromUserId, String toUserId, Money amount);
// throws InvalidSettlementException

List<Settlement> suggestSimplifiedSettlements(String groupId);   // pure read, no mutation
```

### `BalanceSheetService`
```java
void applyExpense(Expense expense);
void applySettlement(Settlement settlement);
Money getBalance(String contextId, String userId, String counterpartyId);
Map<String, Money> getBalancesForUser(String contextId, String userId);
Map<String, Long> computeNetBalancePerUser(String contextId);
Money getAggregatedBalance(List<String> contextIds, String userId, String counterpartyId);
```

---

## 9. Core Implementation

The full, compiling implementation lives under
[`src/main/java/lld/splitwise/`](src/main/java/lld/splitwise/). Key files to
read first, in dependency order:

1. [`model/Money.java`](src/main/java/lld/splitwise/model/Money.java) — the
   precision foundation everything else builds on.
2. [`model/Expense.java`](src/main/java/lld/splitwise/model/Expense.java),
   [`model/ExpenseShare.java`](src/main/java/lld/splitwise/model/ExpenseShare.java) —
   the core immutable domain facts.
3. [`split/SplitStrategy.java`](src/main/java/lld/splitwise/split/SplitStrategy.java),
   [`split/EqualSplitStrategy.java`](src/main/java/lld/splitwise/split/EqualSplitStrategy.java),
   [`split/ExactSplitStrategy.java`](src/main/java/lld/splitwise/split/ExactSplitStrategy.java),
   [`split/PercentSplitStrategy.java`](src/main/java/lld/splitwise/split/PercentSplitStrategy.java) —
   the Strategy pattern implementation, with full remainder-distribution logic.
4. [`ledger/BalanceSheet.java`](src/main/java/lld/splitwise/ledger/BalanceSheet.java) —
   the pairwise ledger and its locking strategy (read this one carefully for §11).
5. [`ledger/BalanceSheetService.java`](src/main/java/lld/splitwise/ledger/BalanceSheetService.java) —
   owns all `BalanceSheet`s, applies domain events to them.
6. [`algorithm/DebtSimplifier.java`](src/main/java/lld/splitwise/algorithm/DebtSimplifier.java) —
   the greedy max-heap debt-simplification algorithm, with full correctness
   proof and complexity analysis in its javadoc (see §10 below for the summary).
7. [`service/ExpenseService.java`](src/main/java/lld/splitwise/service/ExpenseService.java),
   [`service/SettlementService.java`](src/main/java/lld/splitwise/service/SettlementService.java) —
   orchestration/idempotency.
8. [`app/SplitwiseDemo.java`](src/main/java/lld/splitwise/app/SplitwiseDemo.java) —
   a runnable `main()` exercising every flow end-to-end (all 3 split types,
   balance queries, debt simplification, settle-up). Run it with:
   ```bash
   javac -d out $(find src -name '*.java')
   java -cp out lld.splitwise.app.SplitwiseDemo
   ```

No pseudocode is used anywhere in `src/` — every class shown above is
complete, compiling, and covered by the test suite in `tests/`.

---

## 10. Data Structures and Algorithms

| Component | Data structure | Why | Complexity |
|---|---|---|---|
| `BalanceSheet.balances` | `HashMap<UnorderedUserPair, Long>` | O(1) average lookup/update per pair; only non-zero balances are stored (sparse), so memory is O(active pairs), not O(n²). | get/put: O(1) avg |
| `BalanceSheet` membership sign | Canonical pair ordering (`first < second` lexicographically) + signed `long` | Avoids storing `(A,B)` and `(B,A)` as two separate, potentially-inconsistent entries. | O(1) to canonicalize (one string compare) |
| `Group.memberUserIds` | `CopyOnWriteArraySet<String>` | Reads (membership checks on every expense) vastly outnumber writes (join/leave); lock-free reads. | read: O(n) contains (n = group size, typically small); write: O(n) copy |
| `ExpenseRepository` indices | `ConcurrentHashMap<String, Expense>` + per-group `CopyOnWriteArrayList<Expense>` | O(1) lookup by id; O(1) amortized append per group; safe concurrent iteration for reads. | lookup O(1), append O(group size) amortized |
| Idempotency key dedup | `ConcurrentHashMap<String,String>` with `putIfAbsent` | Atomic "first writer wins" semantics without an explicit lock. | O(1) |
| **Debt simplification** | **Two max-heaps (`PriorityQueue`)**: one for net creditors, one for net debtors | See full analysis below. | **O(n log n)** |

### Debt Simplification — full correctness & complexity analysis

**Problem:** Given each user's *net* balance in a group (positive = owed
money, negative = owes money; guaranteed to sum to exactly zero), find a
small set of `(from, to, amount)` transactions that, when applied, brings
every user's net balance to exactly zero.

**Algorithm** (implemented in
[`DebtSimplifier.simplify`](src/main/java/lld/splitwise/algorithm/DebtSimplifier.java)):
1. Partition users with non-zero net balance into a **max-heap of creditors**
   (ordered by balance, descending) and a **max-heap of debtors** (ordered by
   `|balance|`, descending).
2. While both heaps are non-empty: pop the largest creditor `C` and largest
   debtor `D`; settle `min(C.amount, |D.amount|)`; emit one settlement
   transaction `D -> C`; push back whichever of `C`/`D` has a remaining
   non-zero balance.
3. Terminate when both heaps are empty.

**Correctness (proof sketch):**
- *Invariant:* at every iteration, `sum(creditors' remaining) ==
  sum(|debtors' remaining|)`. This holds initially because the ledger's net
  balance always sums to zero (a structural guarantee — see §5), and each
  iteration subtracts the *same* `settleAmount` from one creditor and adds it
  back to one debtor's magnitude, preserving the equality.
- *Progress:* each iteration fully zeroes out **at least one** of the two
  popped accounts (whichever had the smaller magnitude). Since there are at
  most `n` non-zero accounts, the loop runs at most `n - 1` iterations (the
  final iteration always zeroes out both remaining accounts simultaneously,
  since by the invariant their magnitudes must then be equal).
- *Validity of output:* because the loop only terminates when both heaps are
  empty — i.e. every balance is exactly zero — applying the full list of
  emitted settlements is guaranteed to reconcile the entire ledger. This is
  verified concretely in `DebtSimplifierTest.resultingSettlementsExactlyZeroOutEveryNetBalance`
  and `.verifiedCounterexampleWhereGreedyIsNotGloballyOptimal` (which checks
  the zero-out property even for the sub-optimal case below).

**Complexity:** let `n` = number of users with a non-zero net balance.
Building both heaps is `O(n log n)` (or `O(n)` with true heapify, though
Java's `PriorityQueue` constructor from a collection doesn't guarantee
linear-time heapify — irrelevant in practice since group sizes are small).
The main loop runs at most `n - 1` times, each doing O(1) pops and O(log n)
pushes: **O(n log n)** total time, **O(n)** space.

**This bounds transaction count at `n - 1`, but is NOT always globally
minimal.** Finding the true minimum number of transactions ("Optimal Account
Balancing", LeetCode 465) is equivalent to partitioning the non-zero balances
into the maximum number of disjoint subsets that each independently sum to
zero (then solving each subset optimally) — a problem that generalizes
subset-sum/partition and is NP-hard in general. The known exact algorithms use
exponential-time backtracking/DFS over subsets of the non-zero balances
(tractable only for small `n`, roughly up to 10-12 in practice), not a
polynomial algorithm.

**Concrete, verified counterexample** (found by brute-force search, see
`DebtSimplifierTest.verifiedCounterexampleWhereGreedyIsNotGloballyOptimal`):
for the 6-user balance set `{U0: -8, U1: +6, U2: -2, U3: +3, U4: +4, U5: -3}`
(all amounts in the same unit; sums to 0), this greedy algorithm produces
**5** transactions, while the true minimum is **4** (achievable by first
noticing `{U0: -8, U1: +6, U4: +... }`-style sub-groupings that the greedy
largest-vs-largest heuristic doesn't discover, since it never looks ahead for
exact sub-zero-sum partitions — it only ever reduces the single largest
magnitudes against each other).

**Why this trade-off is the right one for this system** (see also §16): the
greedy algorithm is polynomial, deterministic, and simple to reason about and
test; the exact algorithm is exponential and only tractable for toy group
sizes. A production expense-splitting app overwhelmingly has small-to-medium
groups (a handful to a few dozen members) where `n - 1` vs. the true optimum
differs by at most one or two transactions in the overwhelming majority of
real balance distributions — not worth exponential-time computation for.


---

## 11. Concurrency and Thread Safety

This section is mandatory even for an "in-memory, single-process" system,
because the moment two HTTP requests can be in flight at once (which is true
even for a single JVM serving concurrent requests), every piece of shared
mutable state needs an explicit thread-safety story.

### Shared mutable state inventory
| State | Mutated by | Thread-safety mechanism |
|---|---|---|
| `BalanceSheet.balances` (`HashMap`) | `recordDebt` (writer), `getBalance`/`getBalancesForUser`/`computeNetBalancePerUser` (readers) | `ReentrantReadWriteLock`, one per `BalanceSheet` (per context). All reads/writes go through the lock; the backing map itself is a plain (non-concurrent) `HashMap`, which is safe here *only* because every access is already lock-guarded — mixing a non-concurrent map with ad-hoc unguarded access would be a bug. |
| `Group.memberUserIds` | `addMember`/`removeMember` (writers), `isMember`/`memberIds` (readers) | `CopyOnWriteArraySet` — lock-free, safe-for-iteration snapshot semantics. |
| `*Repository` backing maps | `save` (writer), `findById`/etc. (readers) | `ConcurrentHashMap` (and `CopyOnWriteArrayList` for per-group expense lists) — all individual operations are already atomic; no external lock needed since there is no multi-step read-modify-write across *different* keys in these repositories. |
| `ExpenseRepository` idempotency map | `registerIdempotencyKey` | `ConcurrentHashMap.putIfAbsent` — atomic "first writer wins", see below. |

### Why `BalanceSheet` needs an explicit lock (and the others mostly don't)
A single expense touches **multiple** ledger entries at once (the payer vs.
every other participant) — this is a genuine multi-step read-modify-write
across several map keys that must be applied as one atomic unit, which a
`ConcurrentHashMap` alone cannot provide (its atomicity guarantees are
per-key, not across keys). This is exactly the scenario where an explicit
lock is required, not optional.

### Lock granularity decision: one `ReentrantReadWriteLock` per context (group)
- **Why not per-pair locks (finer)?** A single expense can touch up to `N-1`
  pairs simultaneously (N = participants). Locking multiple pairs requires a
  strict global lock-ordering protocol (e.g. always acquire pair-locks in
  sorted user-id order) to avoid deadlock when two concurrent expenses touch
  overlapping pairs in different orders. This is real complexity for little
  payoff here, since expenses within the *same* group already contend on
  nearly the same pair-set — finer-grained locking wouldn't meaningfully
  increase parallelism *within* a group, only across groups, which
  per-context locking already gives for free.
- **Why not one global lock (coarser)?** That would serialize expense
  additions across *unrelated* groups sharing no users — an unnecessary
  bottleneck as the number of concurrent, independent groups grows.
- **Why read-write (not a plain `synchronized`/mutex)?** Balance *queries*
  are expected to vastly outnumber balance *writes* (every screen render
  reads balances; only explicit user actions write them). A read-write lock
  lets unlimited concurrent readers proceed without blocking each other,
  serializing only against the rarer writers.
- **Deadlock freedom:** exactly one `BalanceSheet` (one lock) is ever held at
  a time per expense/settlement, because an expense belongs to exactly one
  context. There is no nested lock acquisition anywhere in this class, so
  there is no lock-ordering deadlock risk. Cross-context aggregation
  (`BalanceSheetService.getAggregatedBalance`) acquires each context's lock
  **one at a time, sequentially** (never two at once) — also deadlock-free.

### Concurrent expense additions — the specific scenario this task calls out
Two threads adding different expenses to the *same* group concurrently:
```java
BalanceSheet ledger = ledgersByContext.get(groupId); // same instance for both threads
// Thread 1: ledger.applyExpenseShares(Alice, {Bob: $3, Carol: $3})   // holds writeLock for the whole batch
// Thread 2: ledger.applyExpenseShares(Dave, {Alice: $5})             // blocks until Thread 1's writeLock releases
```
`BalanceSheet.applyExpenseShares` acquires the context's `writeLock()` **once**
for the entire multi-participant expense (every share is applied inside that
single critical section via a private `applyDebtLocked` helper), not once per
participant. This is a deliberate design choice, not an accident: an earlier
version of this code called the single-pair `recordDebt` once per
participant from a loop in `BalanceSheetService`, which meant each pairwise
update was atomic but the *expense as a whole* was not — a concurrent reader
could observe, e.g., only 2 of 3 participant debts applied. `applyExpenseShares`
closes that window by making the whole expense one critical section, so two
expense applications to the same group are fully serialized against each
other and no reader ever observes a partially-applied expense. The identical
pattern is used for settlements via `BalanceSheet.trySettle`, which validates
the debt still exists *and* applies the reduction under one lock acquisition
— closing the equivalent check-then-act race for double-settlement (see
`STAFF_LEVEL_DEEP_DIVE.md` Q2–Q3 for the failure mode this avoids and why a
naive two-step implementation would not). This is exercised directly by
`ExpenseServiceConcurrencyTest.manyThreadsAddingExpensesConcurrentlyNeverCorruptTheLedger`,
which runs 16 threads × 200 expenses each against one shared group and
asserts (a) zero failures, (b) the net-balance-sums-to-zero invariant holds
exactly afterward, and (c) the total credit extended to payers exactly
matches the total debt accumulated by debtors (no lost or duplicated update).

### Idempotency under concurrent retries
`ExpenseService.addExpense` with a non-null `idempotencyKey`:
1. First checks `findByIdempotencyKey` (fast path for an already-resolved key).
2. Computes shares and constructs the `Expense` (cheap, no side effects yet).
3. Calls `registerIdempotencyKey(key, expenseId)`, which is an atomic
   `ConcurrentHashMap.putIfAbsent` — exactly one concurrent caller "wins" this
   race, no matter how many threads call it simultaneously with the same key.
4. The winner proceeds to persist and apply the expense to the ledger; every
   loser defers to the winner's already-registered expense id.

This is verified in
`ExpenseServiceConcurrencyTest.idempotencyKeyPreventsDuplicateExpenseCreationUnderConcurrentRetries`
(20 threads racing on the same idempotency key resolve to exactly one expense
and exactly one application to the ledger).

### What is explicitly NOT addressed at this scope (see §17)
- Cross-JVM/distributed locking (this is a single-process design).
- Optimistic concurrency control / versioning for a persisted (non-in-memory)
  ledger — a production system would likely use per-row version numbers or
  database-level row locks instead of an in-JVM `ReentrantReadWriteLock`.

---

## 12. Persistence and Database Design

This implementation is **deliberately in-memory** (see `repository/InMemory*`
classes). Rationale: for an LLD interview, the interesting correctness
questions (precision, invariants, locking, algorithmic correctness) are
orthogonal to *where* bytes are stored, and prematurely dragging in a real
database/ORM both adds incidental complexity and, in interviews, tends to
eat time that's better spent on the above. The `*Repository` **interfaces**
are written exactly as they would be for a persistent-backed implementation,
so swapping in a real datastore is a localized, additive change.

### What a persistent design would look like (for the "Production Evolution" extension)
```sql
CREATE TABLE users (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT NOT NULL UNIQUE
);

CREATE TABLE groups (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    created_by_user_id UUID NOT NULL REFERENCES users(id)
);

CREATE TABLE group_members (
    group_id UUID NOT NULL REFERENCES groups(id),
    user_id UUID NOT NULL REFERENCES users(id),
    PRIMARY KEY (group_id, user_id)
);

CREATE TABLE expenses (
    id UUID PRIMARY KEY,
    group_id UUID NULL REFERENCES groups(id),        -- null = direct expense
    description TEXT NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    paid_by_user_id UUID NOT NULL REFERENCES users(id),
    split_type TEXT NOT NULL,
    idempotency_key TEXT NULL UNIQUE,                 -- enforces "exactly once" at the DB layer
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE expense_shares (
    expense_id UUID NOT NULL REFERENCES expenses(id),
    user_id UUID NOT NULL REFERENCES users(id),
    amount_minor_units BIGINT NOT NULL,
    PRIMARY KEY (expense_id, user_id)
);

CREATE TABLE settlements (
    id UUID PRIMARY KEY,
    group_id UUID NULL REFERENCES groups(id),
    from_user_id UUID NOT NULL REFERENCES users(id),
    to_user_id UUID NOT NULL REFERENCES users(id),
    amount_minor_units BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

-- Materialized/cached balances (derivable from expenses+settlements, but
-- cached for read performance -- must be kept in sync transactionally).
CREATE TABLE balances (
    group_id UUID NOT NULL,          -- or a sentinel for the "direct" context
    user_a UUID NOT NULL,            -- canonical: user_a < user_b
    user_b UUID NOT NULL,
    amount_a_owes_b_minor_units BIGINT NOT NULL,
    PRIMARY KEY (group_id, user_a, user_b)
);

CREATE INDEX idx_expenses_group_id ON expenses(group_id);
CREATE INDEX idx_expense_shares_user_id ON expense_shares(user_id);
CREATE INDEX idx_balances_user_a ON balances(group_id, user_a);
CREATE INDEX idx_balances_user_b ON balances(group_id, user_b);
```

**Transactions/isolation:** applying an expense would be one DB transaction
that inserts the `expenses` + `expense_shares` rows *and* upserts the
affected `balances` rows (one `UPDATE ... SET amount = amount + ? WHERE ...`
per affected pair, or `INSERT ... ON CONFLICT DO UPDATE`). `READ COMMITTED`
isolation is sufficient as long as the balance upserts use row-level atomic
increments (not read-modify-write at the application layer, which would
reintroduce a race) — this mirrors exactly how `BalanceSheet.recordDebt`
behaves today under its write lock, just moved into the database engine's
row-locking instead of a JVM lock. The `idempotency_key UNIQUE` constraint
on `expenses` gives the same "exactly once" guarantee as the in-memory
`ConcurrentHashMap.putIfAbsent`, enforced by the database instead.

---

## 13. Failure Scenarios

| Scenario | Handling |
|---|---|
| **Client retries an "add expense" call after a timeout** (partial failure: request may or may not have been applied server-side) | Idempotency key (see §11) guarantees exactly-once application; the retry simply returns the original `Expense`. |
| **Invalid split input** (percentages don't sum to 100, exact amounts don't match the total, duplicate participants, negative amounts) | Rejected synchronously with `InvalidSplitException` *before* any ledger mutation — the system never partially applies an invalid expense. |
| **Expense references a user/group that doesn't exist** | `UserNotFoundException`/`GroupNotFoundException` thrown before any state change. |
| **Expense references a user not in the target group** | `NotAGroupMemberException` — prevents "orphaned" ledger entries for someone who isn't accounted for in that group's membership. |
| **Settling a debt that doesn't exist** (e.g. double-settling, or settling in the wrong direction) | `InvalidSettlementException` — `recordSettlement` checks the current balance before applying. |
| **Concurrent expense additions corrupting shared balances** | Prevented structurally by `BalanceSheet`'s write lock (see §11); covered by a dedicated stress test. |
| **Resource exhaustion** (e.g. extremely large group, pathological expense volume) | Out of scope for correctness but discussed in §14 (Performance) — the data structures used are all O(participants)/O(1)-ish per operation, so a single huge expense (not a huge *history*) is the realistic failure mode, bounded by available heap for the `List<ExpenseShare>`. |
| **Process crash mid-expense-application** (in-memory only: data loss) | At this (deliberately in-memory) scope, a crash loses unpersisted state entirely — this is the primary reason a production system needs the persistence design in §12. With real persistence, the fix is: write the `expenses`/`expense_shares` rows and the `balances` upserts in one DB transaction, so a crash mid-way rolls back atomically (no partial application) and a restart simply re-reads the last-committed consistent state. |
| **Corruption / drift detection** (ledger numbers don't reconcile with expense history, e.g. due to a bug) | Because expenses/settlements are immutable, append-only facts, the *entire* ledger is always re-derivable from scratch by replaying `applyExpense`/`applySettlement` over the full history in order — this "replay to verify" capability is the key recovery/audit property of keeping facts append-only rather than mutating state directly. |
| **Duplicate expense submission without an idempotency key** | Not prevented (by design — the caller opted out); this is documented as the caller's responsibility, matching how most real APIs treat optional idempotency keys. |
| **Clean-up after a user/group is deleted** | Out of scope for this LLD scope (no delete flows implemented), but the natural approach given append-only facts is a tombstone/soft-delete flag rather than a hard delete, to preserve historical/audit integrity of other users' expense history. |

---

## 14. Performance and Scalability

**Bottlenecks and fixes, single-process:**
- `BalanceSheet`'s write lock is the serialization point for a given
  group — under very high write concurrency *to the same group*, this is a
  genuine bottleneck. Fix within a single process: none needed beyond what
  exists, because real-world "same group, many simultaneous writers" traffic
  is modest (a handful of people in one trip, not thousands); if it ever
  became a bottleneck, sharding the lock further (e.g. one lock per *subset*
  of the pair-space) is possible but adds the deadlock-ordering complexity
  discussed in §11, so it should only be done if profiling actually shows
  this as a hot path.
- `Group.memberIds()` returns a fresh `LinkedHashSet` copy on every call
  (defensive copy to prevent external mutation of internal state) — for a
  very large group queried very frequently, this allocation could be
  measurable; a read-mostly cache or returning an unmodifiable *view*
  instead of a copy would be the fix if profiling showed this mattering.
- `ExpenseRepository.findByGroupId` returns a full copy of a group's expense
  history — fine for "show recent expenses" UI pagination at reasonable
  history sizes, but would need actual pagination (`LIMIT`/`OFFSET` or
  keyset pagination) at real production scale (a group with 100k expenses).

**Multi-threaded (within one JVM) optimizations already applied:** read-write
locking for the read-heavy balance ledger; copy-on-write for the
write-infrequent group membership set; lock-free atomic repositories via
`ConcurrentHashMap`.

**Distributed-extension optimizations (explicitly NOT implemented here, to
avoid over-distributing an LLD problem):** sharding the ledger by group id
across multiple nodes (natural, since contexts are already independent
lock domains); read replicas for balance queries; caching aggregated
balances with invalidation on write. These are discussed further in §17 and
are intentionally kept out of the core interview solution — a candidate who
jumps straight to "let's shard this across Kafka partitions" for a
roommate-expense app is over-engineering, not demonstrating staff-level judgment.

---


## 15. Testing Strategy

All tests live under [`tests/lld/splitwise/`](tests/lld/splitwise/) (JUnit 5),
mirroring the `lld.splitwise` package structure. 38 tests, all passing (see
repo verification notes). Breakdown by category:

| Category | Test file(s) | What's covered |
|---|---|---|
| **Unit — precision/value objects** | `MoneyTest` | Exact decimal conversion, no floating-point drift across repeated addition, overflow protection, value-based equality/hashing/ordering. |
| **Unit — split strategies** | `EqualSplitStrategyTest`, `ExactSplitStrategyTest`, `PercentSplitStrategyTest` | Remainder distribution correctness (including a parametrized sweep over `n = 1..23` participants), largest-remainder percentage apportionment, duplicate-participant rejection, negative-amount rejection, exact-sum-mismatch rejection. |
| **Unit — algorithm** | `DebtSimplifierTest` | Already-balanced no-op case, simple two-party case, the `n-1` transaction-count upper bound, the verified sub-optimality counterexample, and a general "settlements always exactly zero out the input balances" property check. |
| **Integration — ledger** | `BalanceSheetServiceTest` | Expense application credits/debits correctly, a payer's own share never creates a self-debt, settlements reduce debt correctly, net balances always sum to zero, and different groups have fully independent ledgers. |
| **Integration — services** | `SettlementServiceTest` | End-to-end: add expenses → suggest simplification → apply all suggestions → assert every net balance is exactly zero. |
| **Negative / boundary** | Spread throughout (e.g. `rejectsAmountsThatDoNotSumToTotal`, `offByOneMinorUnitStillRejected`, `rejectsSettlementWhenNothingIsOwedInThatDirection`) | Off-by-one-minor-unit precision boundaries; invalid input rejected before any mutation. |
| **Concurrency / failure-injection** | `ExpenseServiceConcurrencyTest` | 16 threads × 200 expenses concurrently against one shared group ledger, asserting zero failures and the zero-sum invariant; 20 threads racing on one idempotency key, asserting exactly one expense is ever created. |

**Representative test** (shows the zero-sum-invariant check under real concurrency):
```java
@Test
void manyThreadsAddingExpensesConcurrentlyNeverCorruptTheLedger() throws InterruptedException {
    // ... 16 threads each add 200 equal-split expenses to the same group ...
    var net = balanceSheetService.computeNetBalancePerUser(group.id());
    long sum = net.values().stream().mapToLong(Long::longValue).sum();
    assertEquals(0L, sum, "Sum of all net balances must always be exactly zero (zero-sum invariant)");
}
```

**How to run:**
```bash
javac -d out -cp junit-platform-console-standalone.jar $(find src tests -name '*.java')
java -jar junit-platform-console-standalone.jar --class-path out --scan-class-path
```

---

## 16. Trade-offs and Alternatives

| Decision | Chosen approach | Alternative | Pros/cons | When the alternative wins |
|---|---|---|---|---|
| Money representation | `long` minor units | `BigDecimal` with explicit scale/currency | Chosen: simpler, faster, no rounding-mode decisions scattered everywhere, trivially hashable/comparable. `BigDecimal` alt: handles arbitrary precision & multiple currencies with different decimal scales (e.g. JPY=0, BHD=3) in one type. | When true multi-currency support (different scales per currency simultaneously) is a hard requirement — then `BigDecimal` (or a `Money` type parameterized by `Currency` with a scale lookup) is worth the added complexity. |
| Debt simplification | Greedy max-heap, `O(n log n)`, bounded `n-1` transactions, not globally minimal | Exact optimal via exponential DFS/bitmask search over subsets (LeetCode 465-style) | Chosen: polynomial, deterministic, trivial to reason about/test, "good enough" for realistic group sizes. Alt: mathematically optimal but exponential — only tractable for `n <= ~12`, and the implementation/testing complexity is much higher. | If the product requirement is explicitly "always show the provably fewest possible transactions" *and* groups are guaranteed small (e.g. capped at 8-10 active debtors/creditors), the exact algorithm becomes feasible and worth implementing. |
| Expense mutability | Immutable, append-only (edits = delete + recreate) | True in-place mutable expenses with delta-based ledger updates | Chosen: trivially thread-safe, naturally auditable, ledger is always replayable from history. Alt: saves a "delete+recreate" round-trip and preserves a single expense id across edits, but requires correct delta computation (new shares minus old shares) applied atomically — materially harder to get right under concurrency, and loses the simple append-only audit trail. | If product requires preserving a stable expense id/edit history (e.g. "edited 3 times" UI) rather than a chain of delete+recreate events, in-place mutation with careful delta logic becomes necessary. |
| Ledger lock granularity | One `ReentrantReadWriteLock` per context (group) | Per-user-pair locks (finer) or one global lock (coarser) | Chosen: balances parallelism (independent groups don't block each other) against simplicity (no cross-pair lock-ordering protocol needed, since one expense only ever needs one context's lock). | Per-pair locking would be worth it only if a *single* group routinely has extremely high write concurrency (many simultaneous expense submissions *to the exact same group*) *and* profiling showed the group-level lock as an actual bottleneck — an unusual workload for this domain. |
| Persistence | In-memory (`ConcurrentHashMap`-backed repositories) | Real relational database (see §12 schema) | Chosen: appropriate for LLD-interview scope, keeps focus on domain/concurrency/algorithm correctness. Alt: durable, survives crashes, supports real multi-process/multi-node deployment. | Any real production deployment needs persistence — see §17. |
| Split type extensibility | Strategy pattern (one class per split type) | Single `switch` in `ExpenseService` | Chosen: Open/Closed — new split types added without touching tested code; independently unit-testable splitting edge cases. Alt: fewer files, slightly less indirection for exactly 3 fixed types. | If the split-type set were provably permanently fixed at exactly these 3 (never extended, e.g. a hard regulatory/contractual constraint), the simpler inline `switch` would be a defensible, less-indirect choice. |

---

## 17. Production Evolution

Kept separate from the core interview solution on purpose — these are the
concerns a staff engineer would raise as "here's what changes before this
ships to real users," not things to build during a 45-60 minute interview:

- **Persistence & migrations:** move from in-memory repositories to the
  relational schema in §12, with a migration tool (Flyway/Liquibase) and a
  cached/materialized `balances` table kept in sync transactionally with
  `expenses`/`settlements`.
- **Monitoring & metrics:** emit metrics for expense-creation rate,
  settlement rate, ledger-lock wait time (p50/p99) per group, debt-simplification
  invocation latency and transaction-count-produced (to track how often the
  greedy algorithm's sub-optimality actually matters in practice), and
  idempotency-key collision rate (signals client retry behavior).
- **Structured logging & tracing:** correlate `addExpense`/`recordSettlement`
  calls with a request/trace id, log every ledger mutation (debtor, creditor,
  amount, resulting balance) for audit purposes.
- **Distributed coordination:** if sharded across multiple nodes, shard by
  group id (contexts are already independent lock/ownership domains, so this
  maps naturally) and move from in-JVM `ReentrantReadWriteLock` to
  database-row-level locking or per-shard ownership (e.g. consistent hashing
  of group id to a ledger-owning node) rather than inventing a distributed
  lock from scratch.
- **Fault tolerance:** replicate the persistent store; define a clear
  recovery procedure (replay expenses/settlements in timestamp order to
  rebuild the `balances` cache after a crash or corruption, exactly as
  discussed in §13).
- **API evolution / backward compatibility:** version the public API
  (`/v1/expenses`); treat `SplitInput` variants as an evolving, versioned
  contract (adding a new split type is additive and safe; changing an
  existing one's semantics is not — would need a new `SplitType` value
  instead of mutating `EQUAL`'s meaning).
- **AuthN/AuthZ:** restrict who can add expenses to a group (must be a
  member), who can edit/settle on behalf of another user (should essentially
  always be "no one" except the user themselves, modulo an admin/dispute
  flow), and audit all settlement actions given they represent real money
  movement claims.
- **Multi-currency:** extend `Money` with a `Currency`, reject/convert
  cross-currency ledger operations explicitly, and run debt simplification
  independently per currency within a group.
- **Payment gateway integration:** `recordSettlement` currently models an
  already-happened external payment; a production system would instead
  *initiate* a real payment (e.g. via Stripe/PayPal/UPI) and only call
  `recordSettlement` once that payment gateway confirms success
  (webhook-driven), with a `PENDING`/`CONFIRMED`/`FAILED` settlement state
  machine instead of today's fire-and-forget immutable fact.
