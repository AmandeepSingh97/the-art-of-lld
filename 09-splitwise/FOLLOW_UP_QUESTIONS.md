# Follow-Up Questions — Splitwise (Expense Sharing System)

Twenty-six realistic interviewer follow-ups, categorized exactly as required.
These are the standard, broadly-applicable questions an interviewer asks
after the core design is on the board — as opposed to `STAFF_LEVEL_DEEP_DIVE.md`,
which pressure-tests this *specific* implementation's harder edge cases.
Every answer is grounded in the actual code in `src/`.

---

## Object-Oriented Design

### 1. Why is `Expense` immutable, and what would you lose by making it mutable?

`Expense` (a Java `record`-backed value with validation in its canonical
constructor) has no setters; once constructed, its shares, amount, and payer
never change. An expense is a historical fact ("Alice paid $60 on March 3,
split three ways") — facts shouldn't be editable after recording, only
reversed by a new fact (a correction expense or an adjustment settlement),
exactly like an accounting ledger never edits a posted journal entry. If
`Expense` were mutable: (a) the `shares`-sum-to-`amount` invariant checked in
the constructor could be silently violated by a later field mutation,
(b) two threads holding a reference to the same `Expense` could race on a
field update with no lock protecting it (unlike `BalanceSheet`, which has an
explicit lock), and (c) replaying `ExpenseRepository`'s history to
reconstruct the ledger (a real recovery technique, see README §13) would be
unsound if entries in that history could change after being recorded.

### 2. How do `User`, `Group`, and `Expense` relate to each other — composition, aggregation, or association?

`Group` *aggregates* `User` by id (`Set<String> memberUserIds`), not by
object reference — a `Group` does not own its members' lifecycle (a `User`
exists independently and can belong to many groups), so this is a
loose/aggregation relationship, modeled by storing ids and resolving through
`UserRepository` when needed. `Expense` has an *association* to `User` and
(optionally) `Group`, again by id, for the same reason: an `Expense`
shouldn't hold a live, mutable reference to a `User` object it doesn't own.
Using ids everywhere instead of object graphs also keeps `Expense`/`Group`
trivially serializable and avoids accidental shared mutable state leaking
across repositories.

### 3. Why does `ExpenseShare` exist as its own type instead of just using a `Map<String, Money>`?

A raw `Map<User, Money>` loses two things a named type gives you for free:
(a) a documented, enforceable shape — `ExpenseShare(userId, amount)` can be
validated once (non-negative amount) in its own constructor rather than
trusting every call site to check map values, and (b) room to grow — if a
future requirement adds a per-share note or a "paid directly" flag, that's
a new field on `ExpenseShare`, not a breaking change to every method that
currently takes `Map<String, Money>`. This mirrors a broader rule: any time
a primitive pair recurs across multiple method signatures, it is usually a
missing domain type (a "primitive obsession" smell), as `ExpenseShare` avoids.

### 4. Why is `SplitStrategy` an interface rather than an abstract class?

The three strategies (`Equal`, `Exact`, `Percent`) share no common state and
only a tiny amount of common behavior (really just "validate then compute
shares") — there is no shared mutable field or template-method skeleton
worth factoring into a base class. An interface keeps each strategy a
single, independently-testable unit (see `EqualSplitStrategyTest`,
`ExactSplitStrategyTest`, `PercentSplitStrategyTest`) and leaves room for a
strategy to be implemented as a lambda or a record in the future if it ever
becomes pure data-in/data-out, which an abstract class would foreclose.

---

## SOLID Principles

### 5. Where does this design most clearly demonstrate the Open/Closed Principle?

Adding a new split type (e.g. "by shares/units", asked about explicitly in
`STAFF_LEVEL_DEEP_DIVE.md` Q6) requires: one new `SplitInput` record variant,
one new `SplitStrategy` implementation, and one new `case` in
`SplitStrategyFactory`. Nothing in `ExpenseService`, `BalanceSheet`, or any
existing strategy changes — the system is open for this extension and
closed for modification of already-tested code. The sealed `SplitInput`
hierarchy actually makes the compiler *help* enforce this: the factory's
switch over `SplitInput` subtypes is exhaustive, so adding a new `SplitInput`
variant without adding a matching strategy case is a compile error, not a
silent runtime gap.

### 6. Where does Single Responsibility show up most concretely?

`BalanceSheet` *only* owns ledger arithmetic and its lock; it knows nothing
about `Expense` or `Settlement` as domain concepts. `BalanceSheetService`
translates domain events into ledger calls but owns no locking itself.
`DebtSimplifier` is a pure algorithm with zero I/O or domain-object
dependencies (it takes a `Map<String, Long>` and returns transactions) —
it could be lifted into its own library with no changes. Each class has
exactly one reason to change: ledger representation, event translation, or
algorithm correctness, respectively — never more than one at a time.

### 7. Does `SettlementService` depending on both `BalanceSheetService` and `DebtSimplifier` violate the Dependency Inversion Principle?

No — both dependencies are concrete classes here (acceptable for an LLD of
this scope, where there is exactly one real implementation of each and no
test double is actually needed beyond what the real class already provides
cheaply), but the *shape* of the dependency already respects DIP in spirit:
`SettlementService` depends on `DebtSimplifier`'s narrow, pure
`simplify(Map<String,Long>, Supplier<String>, String)` method, not on any
ledger internals, and depends on `BalanceSheetService`'s narrow
balance-query/mutation surface, not on `BalanceSheet` or its lock directly.
If a second ledger backend were ever introduced, extracting a
`BalanceSheetService` interface at that point would be a non-breaking
refactor precisely because callers never reached past that boundary.

### 8. Where would Liskov Substitution actually matter in this design, and is it upheld?

The only true subtype polymorphism here is `SplitStrategy` (and its sealed
`SplitInput` family). LSP requires that any `SplitStrategy` be substitutable
wherever `SplitStrategy` is expected without breaking caller expectations.
All three implementations uphold this: each throws the same exception type
(`InvalidSplitException`) for invalid input, each returns `List<ExpenseShare>`
summing exactly to the expense total (verified independently in each
strategy's test), and none has a hidden precondition the others don't share
(e.g. none requires the caller to pre-sort shares, none mutates its input).
A strategy that silently rounded instead of throwing on an invalid split
would violate LSP by surprising callers who rely on the "always sums exactly
or throws" contract the other two uphold.

### 9. How does Interface Segregation show up in the repository layer?

Each repository interface (`UserRepository`, `GroupRepository`,
`ExpenseRepository`, `SettlementRepository`) exposes only the operations its
own service actually needs (e.g. `ExpenseRepository` adds
`findByIdempotencyKey`/`registerIdempotencyKey`, which only `ExpenseService`
uses — `GroupRepository` has no such methods because groups aren't created
idempotently in this design). No single "fat" `Repository<T>` interface
forces every implementation to stub out methods it doesn't need.

---

## Design Patterns

### 10. Beyond Strategy, what pattern does `SplitStrategyFactory` represent, and why a factory instead of constructing strategies inline?

It's a (simple, non-abstract) Factory — a single place that maps a
`SplitInput`'s runtime type to the correct `SplitStrategy` instance. Without
it, every call site that needs a strategy (today: `ExpenseService`; in a
future UI layer, potentially many more) would duplicate the same `switch`/
`instanceof` dispatch, and adding a new split type would require updating
every call site instead of one factory method.

### 11. Is the repository layer itself a design pattern, and which one?

Yes — the Repository pattern: each repository interface abstracts "a
collection of domain objects" behind a persistence-agnostic API
(`save`, `findById`, `findAllByGroupId`, etc.), so `*Service` classes never
know whether they're talking to an in-memory `HashMap`-backed implementation
(as today) or a future SQL-backed one. This is also what makes the "swap
in-memory for a real database" production-evolution story (README §17) a
localized change: new implementations of the same interfaces, zero changes
to any service.

### 12. Why not use the Observer pattern to notify users when a new expense affects their balance?

It's a legitimate future extension (see README §17, "notifications"), but
deliberately **not** built into the core interview solution, because: (a) it
adds a pub/sub concern (who subscribes, how failures in one observer are
isolated from others, delivery ordering) that's orthogonal to the core LLD
ask (domain model, splits, ledger, simplification), and (b) forcing it in
now would mean `ExpenseService.addExpense` either blocks on notification
delivery (hurting the latency of the write path) or fires-and-forgets
(introducing a failure mode — a crashed notification handler — that has
nothing to do with ledger correctness). The honest answer when asked "would
you add this?" is: yes, as a decoupled consumer of an event log/outbox
(README §17's "event-sourcing-adjacent" direction), not as a hard dependency
threaded through `ExpenseService`.

### 13. Did you consider the Decorator pattern anywhere, and why didn't you use it?

It was considered for adding cross-cutting behavior to split computation
(e.g. a "LoggingSplitStrategy" or "AuditedSplitStrategy" wrapping any
`SplitStrategy`) and rejected for this scope because there is currently
exactly one cross-cutting concern worth naming (none yet implemented) and
introducing a decorator layer before there is a second one to justify the
abstraction would be speculative generality — the engineering-standards
section of this spec explicitly calls out "no forced abstractions." If
logging/auditing/metrics around split computation becomes a real
requirement, `SplitStrategy` being an interface (not a concrete class) means
a decorator can be added later with zero changes to existing strategies.

### 14. Why is `DebtSimplifier` not itself a Strategy (e.g. swappable "greedy" vs. "optimal" simplification algorithms)?

It reasonably could be, and if a second interchangeable implementation were
ever needed (e.g. an exact-but-slower algorithm for small groups where
optimality matters more than speed — see Deep Dive Q9), extracting a
`DebtSimplificationStrategy` interface at that point is a clean,
non-breaking refactor. It was kept as a single concrete class now because
there is exactly one implementation and no current caller that needs to
choose between algorithms at runtime — the same "don't force an abstraction
before a second implementation justifies it" principle as Question 13.

---

## Data Structures

### 15. Why `HashMap<UnorderedUserPair, Long>` for the ledger instead of a nested `Map<String, Map<String, Long>>` (adjacency-list style)?

A nested map stores each edge's balance *twice* (once as `balances[A][B]` and
once as `balances[B][A]`, usually with opposite signs) — every update has to
keep both entries in sync, which is both extra work and an extra place for a
bug to desync the two copies. `UnorderedUserPair` canonicalizes a pair into
one consistent key (e.g. always `(min, max)` by id ordering) with a sign
convention resolved by `pair.signFor(userId)`, so each edge is stored
**exactly once** — there is no second copy that can ever drift out of sync
with the first, by construction.

### 16. Why a max-heap (`PriorityQueue`) for debt simplification instead of just sorting the balances once?

Sorting once is insufficient because every settlement changes at least one
party's remaining balance, which can change *where that party should rank*
relative to the others — not just a replacement at the tail, since the
updated amount could re-enter the middle of the ranking order. A
`PriorityQueue` supports exactly the operation this algorithm repeats n
times: extract-max, modify, and (if still non-zero) re-insert in O(log n),
which a one-time sort does not support efficiently after the first mutation
(you'd need to re-sort or do an O(n) linear scan each round).

### 17. What's the time and space complexity of the whole "add expense → update ledger → compute simplification" pipeline, and what dominates?

Adding an N-participant expense is `O(N)` to compute shares (each strategy)
plus `O(N log 1)` ≈ `O(N)` to apply them to the ledger (N-1 hashmap upserts
under one lock, each O(1) amortized). Debt simplification over a group of M
members with non-zero balances is `O(M log M)` (two heaps, each operation
`O(log M)`, bounded at `M-1` transactions). For realistic group sizes (tens
to low hundreds of members), both are effectively instantaneous; the
dominant cost in a real deployment is almost always network/serialization
and persistence I/O, not the in-memory algorithmic work — which is why
README §14 focuses scalability discussion on sharding/caching/persistence,
not on further optimizing these already-near-optimal in-memory structures.

---

## Java Internals

### 18. Why does `Money` override `equals`/`hashCode`, and what would break if it didn't?

`Money` is used as a map value in tests (and conceptually as a VO compared
by value throughout the domain — e.g. "is this settlement's amount equal to
what's owed?"). Records in Java 17+ auto-generate `equals`/`hashCode`/`toString`
based on every component, which is exactly value-type semantics: two
`Money` instances with the same `minorUnits` are `.equals()` and hash
identically, so they behave correctly as `HashSet`/`HashMap` keys or
elements and in assertion libraries that use `.equals()` (e.g.
`assertEquals(Money.of("10.00"), actual)` in the test suite). Without this
(e.g. if `Money` were a plain class with default `Object.equals`), every
such comparison would silently fall back to reference equality and almost
every test assertion would fail even when the "same" value was produced by
a different code path.

### 19. `SplitInput` is a sealed interface with record implementations — why, and what does `sealed` buy you here?

`sealed interface SplitInput permits EqualSplitInput, ExactSplitInput,
PercentSplitInput` tells the compiler the exhaustive list of subtypes up
front. This lets `SplitStrategyFactory`'s `switch` expression over
`SplitInput` be checked for exhaustiveness at compile time — if a new
`SplitInput` variant is ever added without a corresponding `case`, the build
fails immediately rather than the gap surfacing as a runtime
`NullPointerException` or silent `default` fallthrough. This is strictly
better than an unsealed interface plus a `switch` with a `default: throw`,
which only catches the gap at runtime, and only if that exact code path is
ever exercised.

### 20. Why `record` for `Money`, `ExpenseShare`, `Settlement`, and the `SplitInput` variants, but a plain `final class` for `BalanceSheet`?

Records are appropriate for pure, immutable data carriers whose identity
*is* their field values (two `Money(1000)` are simply "the same amount").
`BalanceSheet` is the opposite: it has real identity (two `BalanceSheet`
instances for different groups are never "equal" regardless of their
current balances) and encapsulated, actively-managed mutable internal state
(the `balances` map, guarded by a lock) — exactly what a record's
value-semantics/auto-generated-equality model is wrong for. A record's
components are also all effectively public via accessors, which would leak
the internal map and lock object; a class with deliberately narrow public
methods is the correct tool here.

---

## Multithreading

### 21. Why `ReentrantReadWriteLock` and not simply `synchronized` methods on `BalanceSheet`?

`synchronized` gives mutual exclusion for both reads and writes — a
balance *query* would block behind, and be blocked by, every other query
and every write, even though two concurrent readers never conflict with
each other (no reader mutates state). Since this workload is read-heavy
(every balance screen render is a read; writes only happen on explicit
expense/settlement actions), a read-write lock lets unlimited readers
proceed concurrently, serializing only the comparatively rare writers
against each other and against readers — a meaningfully better fit for the
actual access pattern, not a pattern used just to show familiarity with
`java.util.concurrent.locks`.

### 22. What's the deadlock-freedom argument for this design, precisely?

A deadlock requires at least two threads each holding a lock the other
needs, in reverse order. In this design, at most one `BalanceSheet`'s lock
is ever held at a time by any thread — there is no code path that acquires
one context's lock while already holding another's (verified by inspection:
`BalanceSheet`'s own methods never call out to another `BalanceSheet`, and
`BalanceSheetService.getAggregatedBalance` explicitly acquires each relevant
context's lock sequentially, releasing one before acquiring the next). With
no nested lock acquisition across two different lock instances anywhere in
the codebase, the classic lock-ordering precondition for deadlock simply
cannot arise — not "is unlikely to arise," but structurally cannot, because
the "holds two locks at once" precondition is never true.

### 23. How would you prove the concurrency claims in this design instead of just asserting them?

Three ways, all actually done here: (1) code inspection — every method that
touches shared mutable state is grep-able and each one's critical section
can be read end-to-end to confirm no non-atomic gap (this is exactly how
the torn-read and double-settle issues were found and then closed — see
`STAFF_LEVEL_DEEP_DIVE.md` Q2–Q3); (2) a stress test with real concurrent
threads exercising the exact shared state under contention and asserting
invariants afterward (`ExpenseServiceConcurrencyTest`, 16 threads × 200
expenses, asserting the net-balance-zero invariant and exact credit/debit
totals); (3) running that test repeatedly (concurrency bugs are often
timing-dependent and don't reproduce every run) — which was done as part of
verifying this submission, with consistent passing results across multiple
runs. None of these alone is proof of absence of bugs (concurrency bugs are
notoriously hard to fully rule out by testing), but together they're a far
stronger claim than "it's locked, so it's fine."

---

## Database and Persistence

### 24. This implementation is entirely in-memory. What would the schema look like if you persisted it to a relational database?

Four core tables map directly onto the domain model: `users(id PK, name,
email UNIQUE)`, `groups(id PK, name)`, `group_members(group_id FK, user_id
FK, PRIMARY KEY(group_id, user_id))`, `expenses(id PK, group_id FK NULL,
paid_by_user_id FK, amount_minor_units BIGINT, description, created_at,
idempotency_key UNIQUE NULL)`, `expense_shares(expense_id FK, user_id FK,
amount_minor_units BIGINT, PRIMARY KEY(expense_id, user_id))`, and
`settlements(id PK, group_id FK NULL, from_user_id FK, to_user_id FK,
amount_minor_units BIGINT, created_at)`. Critically, there is **no**
persisted `balances` table — the pairwise ledger is a derived
materialized view, recomputable at any time by replaying `expenses` +
`expense_shares` + `settlements` in order, which is exactly the recovery
property called out in README §13 ("Failure Scenarios"). A real deployment
would likely still maintain a `balances` table as a cache for read
performance, but treat it as rebuildable, not authoritative.

### 25. Why BIGINT minor-units columns instead of a `DECIMAL`/`NUMERIC` column type for money?

Both are precision-safe (unlike `FLOAT`/`DOUBLE` columns, which reintroduce
exactly the rounding problems `Money` was designed to avoid — see README
§10's discussion of why minor-units `long` was chosen over `BigDecimal` in
the application layer), but `BIGINT` minor-units keeps a single
representation end-to-end: the exact same integer that `Money.minorUnits()`
holds in the JVM is what's stored and compared in SQL, with no
decimal-scale/currency-exponent metadata to keep in sync between the
application and schema layers. A `DECIMAL(19,4)` column is also a completely
valid choice (and arguably more self-documenting in a raw SQL client) — the
trade-off is that it requires the ORM/driver layer to correctly round-trip
fixed-point decimals without ever widening to a floating type, which is an
extra integration point to get right compared to a `long` that's already
exact at every layer.

### 26. What transaction isolation level would you need for applying an expense if this were backed by a real RDBMS, and why?

At minimum **READ COMMITTED** is not sufficient on its own for the
multi-row `expense_shares`/implied-balance-update write — you'd want the
whole "insert expense + all its shares + update the derived balances cache"
wrapped in a single database transaction (atomicity at the SQL level,
mirroring what `BalanceSheet.applyExpenseShares`'s single lock acquisition
gives in-memory) so a crash mid-write can never leave a partially-applied
expense visible to another transaction. For the balance-cache *updates*
specifically, either **SERIALIZABLE** (simplest to reason about, at a
throughput cost) or, more practically at scale, optimistic concurrency via a
per-row version/`updated_at` compare-and-swap (closer in spirit to the
in-memory `trySettle`'s atomic check-and-apply) would avoid the lost-update
anomaly that READ COMMITTED alone permits when two transactions both read
then write the same balance row.

---

## Performance

### 27. What's the single biggest latency contributor in the `addExpense` path today, and would it matter at scale?

In the current in-memory implementation, essentially nothing — a hashmap
lookup, O(N) share computation, and one lock acquisition are all
sub-microsecond operations. In a persisted, networked deployment, the
dominant cost shifts entirely to I/O: the database write (expense + N
shares, ideally one round trip via a batched insert) and, if synchronous,
any downstream notification/webhook call. The fix is standard and already
flagged in README §17: make the ledger write synchronous (it must be, for
correctness) but push anything non-essential-to-correctness (notifications,
analytics events) onto an async outbox/queue so `addExpense`'s latency isn't
coupled to subsystems that don't need to block it.

### 28. If `getBalancesForUser` becomes a hot read path (e.g. called on every app-open), how would you speed it up without compromising correctness?

Two independent levers, usable together: (1) since each `BalanceSheet`
already uses a read-write lock, concurrent reads already don't serialize
against each other — the first lever is simply more replicas/read capacity
if lock contention with writers is the bottleneck, not a redesign; (2) a
short-TTL cache (keyed by `contextId:userId`) in front of
`getBalancesForUser`, invalidated on any write to that context — cheap to
add because `BalanceSheetService` is already the single choke point every
write goes through, so invalidation has exactly one place to hook in. What
would be wrong: caching without invalidation (serves stale balances after a
real expense/settlement) or caching at a layer that bypasses
`BalanceSheetService` (two independent, driftable sources of truth).

---

## Failure Handling

### 29. What happens if the process crashes after persisting an `Expense` to `ExpenseRepository` but before `BalanceSheetService.applyExpense` runs?

This is a genuine partial-failure window in the current code (it isn't
wrapped in a single transaction, because there is no transactional store
underneath the in-memory repositories) — on restart, the ledger would be
missing that expense's effect. The recovery story, and the reason
`ExpenseRepository` stores the full expense (not just an id): replay every
persisted `Expense` and `Settlement` in timestamp order through
`BalanceSheetService` on startup to rebuild the ledger from scratch, exactly
as described for the derived/recomputable `balances` table in Question 24.
This is also why `Expense`/`Settlement` are immutable, fully-self-describing
facts rather than deltas referencing other records — replay doesn't need
anything beyond the record itself.

### 30. A client retries `addExpense` after a timeout, not knowing whether the first attempt actually succeeded server-side. How does this system avoid a duplicate expense?

The caller is expected to supply a client-generated `idempotencyKey` (a
UUID minted once per logical user action, reused across retries of that
same action). `ExpenseService.addExpense` registers that key atomically via
`ExpenseRepository.registerIdempotencyKey` (`ConcurrentHashMap.putIfAbsent`)
— exactly one call with a given key ever "wins" and actually applies the
expense to the ledger; every retry (whether truly concurrent or sequential
after a timeout) resolves to the same already-created `Expense` instead of
creating a second one. This is deliberately a client-supplied key (not a
server-generated one) because only the client can correctly correlate "this
retry is the same logical action as that earlier attempt" — a
server-generated key on every call would make every retry look like a brand
new action.

---

## Production Architecture

### 31. How would you shard this system across multiple machines as the number of groups grows beyond one JVM's memory/CPU?

Shard by `groupId` (consistent hashing over group id to a shard/partition),
since nearly all ledger operations are scoped to a single group — an
expense touches exactly one `BalanceSheet`, and the vast majority of balance
queries are "my balance in group X." This keeps the per-group atomicity
story (one lock per context) intact per-shard; the only operation that
crosses shard boundaries is direct (non-group) friend-to-friend expenses and
cross-group balance aggregation (`getAggregatedBalance`), both of which
already only read/write one context at a time sequentially today, so they
naturally extend to "one RPC per shard, aggregate client-side" without a
distributed transaction.

### 32. What would you monitor in production to catch a ledger-correctness regression before users complain?

A scheduled background job computing, per group, "does `computeNetBalancePerUser`
sum to exactly zero?" — emitted as a gauge/counter metric, alerting on any
non-zero drift (this is a direct invariant check, not a proxy metric).
Alongside that: a counter of `InvalidSplitException`/`InvalidSettlementException`
occurrences (a spike suggests either a client bug sending malformed splits,
or a legitimate UX problem where users frequently attempt invalid actions),
and p50/p99 latency histograms on `addExpense`/`recordSettlement` specifically
split out by "lock wait time" vs. "critical section time" so lock contention
is distinguishable from genuinely slow downstream calls.

### 33. How would you roll out a change to `DebtSimplifier`'s algorithm (e.g. swapping greedy for an exact solver on small groups) safely?

Feature-flag it per-group (or per-request), compute *both* the old and new
algorithm's output for a sample of real traffic, and compare only the
*transaction count* and *net-zero-after-applying* invariant (not exact
transaction equality, since multiple minimal solutions can exist for the
same balances) before trusting the new path — this is a shadow-traffic /
dark-launch pattern. Because `DebtSimplifier.simplify` is a pure function
with no side effects (it only suggests settlements; applying them is a
separate, explicit step), this comparison is safe to run in production
without any risk of double-applying anything.

### 34. What's the operational story if the in-memory `BalanceSheet` for one very large/active group becomes a hot spot (lock contention under real load)?

First confirm it's actually the lock and not downstream I/O (the latency
histogram split from Question 32 answers this directly). If it genuinely is
write-lock contention on one group's `BalanceSheet`, the two real levers
are: (a) reduce the critical section further if possible (it's already a
single hashmap upsert per participant, which is close to minimal), or (b) if
write volume to one group is fundamentally too high for a single lock to
absorb, this is a sign the group itself needs splitting at the product
level (e.g. sub-groups/sub-trips) rather than a purely technical fix — no
amount of lock-striping safely preserves the "N-participant expense applied
as one atomic unit" guarantee this design depends on, since striping would
reintroduce exactly the multi-lock-ordering complexity rejected in README
§11's lock-granularity discussion.


