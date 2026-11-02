# Staff-Level Deep Dive — Splitwise (Expense Sharing System)

Twenty-plus challenging, problem-specific questions a staff-level interviewer
might use to pressure-test this design, each with a reasoned answer grounded
in the actual implementation in `src/`.

---

### 1. Your `BalanceSheet` write lock serializes all expense additions to one group. What's the actual throughput ceiling, and when does it bite?

Each write-lock critical section is O(1) amortized per participant (one hash
lookup + arithmetic per pair), so the ceiling in practice is extremely high —
single hash-map operations under an uncontended lock run at tens of millions
of ops/sec; contention only becomes visible when many threads hammer the
*same* group simultaneously (a "hot group"), and even then the critical
section is short enough that the lock is held for nanoseconds, not
milliseconds. The place this actually bites is correctness, not raw
throughput, which is exactly what Question 2 drills into: a naive
implementation that acquired the lock once *per pair* instead of once *per
expense* would let a concurrent reader observe a partially-applied expense.
`BalanceSheet.applyExpenseShares` closes that by taking the write lock
exactly once for the whole expense (see Q2 for why that matters and how it's
implemented).

### 2. Is the implementation actually free of torn reads across a multi-participant expense, or is that just an assertion?

This deserves a precise answer, not a hand-wave. The naive-looking approach —
calling a single-pair `recordDebt(debtor, creditor, amount)` once per
participant from `BalanceSheetService.applyExpense` — would NOT be atomic as
a whole: each individual call is atomic, but N separate lock acquisitions let
a concurrent reader land in between them and observe, say, 2 of 3
participants credited. That is a real torn-read window if you build it that
way, and early in this design that is in fact how it was first written.

The fix actually implemented: `BalanceSheet.applyExpenseShares(payerUserId,
shares)` takes `lock.writeLock()` **once** and loops over every share inside
that single critical section, calling a private `applyDebtLocked` helper that
assumes the lock is already held. `BalanceSheetService.applyExpense` now
calls this one batch method instead of looping with `recordDebt`. So the
claim "an expense is applied atomically, not torn into N racing updates" is
backed by an actual single-lock-acquisition code path, not an assertion — and
it's exercised by `BalanceSheetServiceTest.applyingAnExpenseCreditsThePayerAndDebitsOtherParticipants`
plus the concurrency stress test in `ExpenseServiceConcurrencyTest`, both of
which pass. The general lesson, worth stating in an interview even when you
get it right the first time: *every atomicity claim about a multi-key update
must name the exact critical section that makes it true* — "it's locked" is
not an answer if the lock is acquired and released multiple times across the
operation you're claiming is atomic.

### 3. Two debtors concurrently try to settle the exact same debt (double-settle race) — walk through what happens.

This is the check-then-act hazard to watch for: if the design read the
balance under a `readLock` (to validate "is this positive?"), then separately
reduced it under a fresh `writeLock`, two threads could both pass the read
check before either applied its write — both settling the same $10 debt and
netting it to -$10 (flipping who owes whom), when only one settlement should
have succeeded.

The implementation avoids this by never splitting the check from the act:
`BalanceSheet.trySettle(debtorUserId, creditorUserId, amount)` takes the
write lock **once**, re-reads the current debt and validates it's still
positive *under that same lock*, and only then applies the reduction,
returning `false` (no mutation) if the debt was already gone. This is the
ledger-level equivalent of compare-and-swap: the "compare" and the "swap"
share one critical section, so no other thread can observe or mutate state
between them. `SettlementService.recordSettlement` calls this single atomic
method instead of a separate `getBalance` + `applySettlement` pair, and
`SettlementServiceTest.rejectsSettlementWhenNothingIsOwedInThatDirection`
pins down the resulting behavior (reject cleanly, no partial mutation) for
the no-debt case; the double-settle race itself can't be deterministically
reproduced in a unit test (it depends on thread scheduling), but the single
-critical-section implementation is what eliminates the race by
construction, not by getting lucky with timing.

### 4. The payer in an N-person equal split sometimes isn't actually a participant. What breaks?

Nothing breaks in the current code — `ExpenseService.addExpense` passes
whatever `SplitInput` the caller supplies, and if the payer's id isn't in
the participant list, the payer simply gets zero shares of their own expense
and every participant owes them their full share. This is actually correct,
intentional behavior (e.g. "Alice treats the whole group to dinner and pays
nothing herself") — but it's worth being explicit that the system does *not*
require the payer to be a participant, since an interviewer may probe this
assuming it's an oversight rather than a deliberate, defensible choice.

### 5. What's the first thing that breaks at 10 million users?

Nothing in the *domain model* breaks — `User`/`Group`/`Expense` are tiny,
independent objects with no O(n) scans over the whole user base anywhere.
The actual first failure point is **in-memory storage itself**:
`ConcurrentHashMap`-backed repositories keep everything in one JVM's heap,
which simply cannot hold 10M users' worth of expense history. The *second*
failure point, assuming persistence is added, is the **lock-per-group
design under extreme fan-out** — with millions of independent groups, each
needing its own `BalanceSheet` + `ReentrantReadWriteLock` object, the
`ledgersByContext` map itself (and the per-group lock objects) become a
non-trivial memory overhead (a few hundred bytes per group minimum); at 10M
*active* groups this is tens of GB just for lock bookkeeping, pushing toward
sharding the ledger ownership across multiple nodes (see README §17) rather
than one JVM holding every group's lock.

### 6. If you had to add "split by shares/weights" (e.g. 2 units vs 1 unit) without touching existing code, what exactly would you write and why is this safe?

Add `ShareSplitInput(Map<String, Integer> weightsByUserId)` as a new record
implementing the `SplitInput` sealed interface, add `ShareSplitStrategy
implements SplitStrategy` computing each user's share as
`total * weight / sum(weights)` (using the same largest-remainder
reconciliation technique as `PercentSplitStrategy`), add a `SHARE` value to
the `SplitType` enum, and add one new branch to
`SplitStrategyFactory.forType`'s switch expression. Because `SplitInput` is a
*sealed* interface, the compiler will actually force you to update any
exhaustive `switch` over `SplitInput` subtypes elsewhere in the codebase (there
are none today, but if there were, this is exactly the safety net sealed
interfaces provide). No existing `SplitStrategy` implementation,
`ExpenseService`, or `BalanceSheet` code needs to change — this is the
Open/Closed Principle paying off concretely, not just as a slogan.

### 7. A mid-operation crash: your process dies right after persisting an Expense but before applying it to the BalanceSheet. What state are you in, and how do you recover?

At the current (in-memory) scope: total data loss on crash, full stop — this
is explicitly out of scope and documented as such (README §13). In a
persistent design (§12), the fix is making `expense` + `expense_shares`
insertion and the `balances` upsert part of **one database transaction** —
either both commit or neither does, so this specific interleaved-crash state
(persisted but not applied) becomes impossible by construction, rather than
something to detect-and-repair after the fact. If, for performance or
architectural reasons, the ledger application genuinely had to be a separate
step (e.g. an async consumer reading from an outbox table), the recovery
story would be: because expenses are immutable append-only facts, you can
always rebuild `balances` from scratch by replaying every `expenses`/
`expense_shares` row in timestamp order — this replay-based recovery is the
entire reason the design insists on expenses being immutable facts rather
than allowing in-place mutation.

### 8. Why didn't you make `BalanceSheet.balances` a `ConcurrentHashMap` instead of a locked plain `HashMap`? Wouldn't that remove a lock?

Because a `ConcurrentHashMap` only gives you atomicity *per key* (e.g.
`compute`/`merge` are atomic for one key), but a single expense needs
atomicity *across multiple keys* (every participant pair) relative to
concurrent readers computing an aggregate like `computeNetBalancePerUser`,
which iterates the *entire* map. If the backing map were a
`ConcurrentHashMap` with no external lock, a `computeNetBalancePerUser` call
could iterate a snapshot that mixes pre- and post-expense entries for
different pairs (since `ConcurrentHashMap`'s iterator offers only weak
consistency, not a true snapshot), silently breaking the "net balances sum
to zero" invariant that other code (and tests) rely on. The explicit
`ReentrantReadWriteLock` is what gives `computeNetBalancePerUser` (and
`getBalancesForUser`) their *all-or-nothing* snapshot view — which a plain
`ConcurrentHashMap` cannot provide across multiple keys.

### 9. Your debt-simplification algorithm is greedy and provably not always optimal. Why ship a provably-suboptimal algorithm instead of the correct one?

Three staff-level reasons, in order of importance: (1) **the "optimal"
algorithm is exponential-time** (NP-hard in general, exact solvers use
exponential subset search) — for groups beyond a dozen or so active
debtors/creditors it's not just slower, it's *infeasible*, so it can't
actually be the production algorithm for arbitrary group sizes regardless of
preference; (2) **the greedy algorithm's worst-case gap is small and
bounded** — it never exceeds `n-1` transactions, and empirically the gap to
true-optimal is usually 0-2 extra transactions, which is not a
user-perceptible UX difference ("pay these 4 people" vs. "pay these 3
people" are both perfectly usable); (3) **polynomial-time determinism is
itself valuable** — a `O(n log n)` algorithm has predictable, boring
performance characteristics that are easy to capacity-plan for and to keep
correct under code changes, whereas exponential backtracking has a nasty
habit of degrading unpredictably on adversarial inputs. Shipping the
"textbook optimal" algorithm here would be optimizing for a property (exact
minimality) the product doesn't actually need, at a real cost (unpredictable
latency, harder-to-reason-about code) — that's a bad engineering trade,
not a more "correct" one.

### 10. Could you cache `computeNetBalancePerUser` results instead of recomputing from the pairwise map every time?

Yes, and it's a reasonable production optimization — but it reintroduces a
cache-invalidation problem: every `recordDebt` call would need to also
update a per-user running total (another, smaller, atomic update), which is
actually *more* total work per write (two updates instead of one) in
exchange for making *reads* `O(1)` instead of `O(pairs in context)`. Given
balance queries are expected to dominate writes (README §11), this is
likely a good trade in production — but it should be introduced
deliberately, with its own invariant tests (does the cached net total still
always sum to zero after N concurrent updates?), not bolted on casually,
since it adds a second piece of derived state that must never drift from
the source-of-truth pairwise map.

### 11. What happens if a group has 500 members and someone adds a single EQUAL-split expense across all of them?

`EqualSplitStrategy.computeShares` is `O(n)` in participants — trivially
fine at n=500. `BalanceSheetService.applyExpense` then calls `recordDebt`
499 times (skipping the payer), each `O(1)` under its own lock acquisition —
~500 lock acquisitions for one expense is not expensive in absolute terms
(sub-millisecond), but as discussed in Q1/Q2, this is 499 *separate* critical
sections, not one atomic one, widening the torn-read window proportionally
to group size. This is the kind of thing that looks fine in a demo with 4
users and becomes a more visible correctness concern precisely at this kind
of scale — worth mentioning proactively.

### 12. How would you add a new requirement — "expenses can have comments" — without touching existing tested code?

Add a new, independent entity `Comment(id, expenseId, authorUserId, text,
createdAt)` with its own `CommentRepository`, and a new `CommentService` that
takes an `ExpenseRepository` (to validate the expense exists) as a
collaborator. Zero changes needed to `Expense`, `BalanceSheet`, or any
`SplitStrategy` — comments are a pure addition hanging off an expense id,
not a modification of the expense's financial semantics. This is a good
litmus test for whether your domain model is well-factored: a feature that's
"attached to" an entity rather than "core to" its invariants should never
require touching that entity's class.

### 13. Your `Group` uses `CopyOnWriteArraySet` for membership. What's the actual cost model, and when does that choice stop being correct?

Every write (`addMember`/`removeMember`) copies the *entire* backing array —
`O(current size)`. For a 500-member group with frequent membership churn
(e.g. a public event group where people join/leave constantly), this becomes
genuinely expensive relative to a plain `synchronized HashSet` or
`ConcurrentHashMap.newKeySet()`. The choice is correct specifically because
this domain's actual access pattern is "read membership on every expense
addition (frequent), write membership rarely (joining/leaving a trip group
is a rare event relative to logging expenses)" — if that assumption flips
(a social-network-scale group with constant churn), `CopyOnWriteArraySet`
stops being the right choice and a `ConcurrentHashMap.newKeySet()` (lock-free
reads *and* writes, no full-array copy) would be more appropriate.

### 14. Why is `ExpenseShare.amount` validated to be non-negative but not strictly positive? Isn't a zero-dollar share meaningless?

A zero-dollar share is actually meaningful and intentionally allowed: it
represents "this person is logically a participant in this expense event
(e.g. for audit/history purposes) but owes nothing toward it" — e.g. a
percentage split where someone is assigned 0%. Rejecting it would force
callers to omit zero-share participants from the shares list entirely, losing
the record that they were considered at all. This is a deliberate modelling
choice, not an oversight — but it's worth being ready to defend either way
if challenged.

### 15. Walk through exactly what happens if `PercentSplitStrategy` receives percentages like 33.333333% × 3 (not exactly summing to 100 due to a client-side float bug).

The epsilon check (`EPSILON = 0.01`) tolerates small deviations from exactly
100 — three 33.333333% values sum to 99.999999%, well within the 0.01
tolerance, so it's accepted. The actual per-user minor-unit computation then
proceeds using the *exact* `BigDecimal` percentages supplied (not a
renormalized-to-exactly-100 version), and the largest-remainder step
reconciles the final total to the expense's total exactly — so the
*output* is always exactly correct regardless of small input imprecision;
only the *distribution* among users is marginally affected by how far off
each individual percentage was. This is the right design: validate loosely
on the input side (to avoid rejecting benign float noise from clients),
but enforce exactness strictly on the ledger-affecting output side.

### 16. Could two different groups ever deadlock against each other given your locking design?

No — and this is provable, not just asserted: `BalanceSheetService` never
acquires two different `BalanceSheet` locks within the same call stack
(`getAggregatedBalance` loops over contexts and calls the already-self-
contained `getBalance` once per context, each of which acquires and releases
its own lock fully before the next iteration begins — there is no moment
where two locks are held simultaneously by the same thread). Since
deadlock requires a cycle of threads each holding one lock while waiting
for another, and no code path here ever holds more than one `BalanceSheet`
lock at a time, a deadlock cycle is structurally impossible. This is a much
stronger (and more interview-impressive) answer than "I don't think it
deadlocks" — it's an argument from the actual lock-acquisition pattern.

### 17. If you had exactly 2 weeks to ship a simplified version of this, what would you cut, and what would you absolutely keep?

**Cut:** PERCENT split (keep only EQUAL and EXACT — covers the large
majority of real usage); debt simplification (ship raw pairwise settle-up
only; it's a nice-to-have, not core); idempotency keys (accept the small
risk of client-retry duplicates initially, add later); the read-write lock
nuance (a single `synchronized` per group is simpler to implement correctly
under time pressure, at some throughput cost that's fine at launch scale).
**Never cut:** integer-minor-units money representation (retrofitting this
after launch, once real financial data exists in `double`/float form, is
extremely painful and risky); the Strategy pattern shape for splits (costs
almost nothing extra and avoids a rewrite the moment a 2nd split type is
needed, which happens almost immediately in practice); basic validation
(exact-sum reconciliation) — a system that can silently drift out of balance
is worse than a system with a missing feature.

### 18. How would you detect, in production, that the ledger has silently drifted (some bug caused shares to stop summing to totals)?

Run a periodic reconciliation job: for every group, replay all
`expenses`/`settlements` from scratch (summing all `ExpenseShare` amounts per
pairwise relationship) and diff the replayed result against the live/cached
`balances` table; alert on any non-zero diff. This is only possible *because*
expenses/settlements are immutable, ordered, append-only facts — the
replay-based detection strategy is a direct, practical payoff of the
immutability design decision made in §4/§16 of the README, not an
independent afterthought.

### 19. Your `Expense` constructor throws `IllegalStateException` if shares don't sum to the total — but by the time you're constructing `Expense`, shares already came from a trusted `SplitStrategy`. Isn't this check redundant/dead code?

It's deliberately redundant — defense in depth, not dead code. Today, yes,
the only caller of `Expense`'s constructor is `ExpenseService` using
strategy-computed shares that are already guaranteed correct. But
`Expense`'s constructor is `public`, reachable from any future code path
(a bulk-import tool, a test fixture builder, a future "restore from backup"
feature) that might construct an `Expense` directly without going through a
`SplitStrategy` at all. The invariant is *most critical* precisely in
`Expense` (the thing that ends up driving real financial ledger updates), so
checking it there — even if theoretically redundant with upstream checks
today — is the right place to make the invariant unconditionally
non-bypassable, rather than trusting every future caller to remember to
validate it themselves.

### 20. Why `ReentrantReadWriteLock` and not `StampedLock` (which is generally faster for read-heavy workloads in modern JDKs)?

`StampedLock`'s optimistic-read mode is indeed faster under light write
contention, but it does not support reentrancy and has a meaningfully more
error-prone API (manual stamp validation, no automatic fallback to blocking
read if a value changed mid-read, no interruptible lock acquisition without
extra plumbing). Given this is an LLD-interview-scope, single-process
design where lock-acquisition overhead is nowhere near the actual
bottleneck (hash-map operations are already microseconds), the correctness
and readability benefits of `ReentrantReadWriteLock`'s simpler, better-known
API outweigh `StampedLock`'s raw throughput edge. This would be revisited
only if profiling in a real production deployment specifically identified
read-lock acquisition overhead as a measured hot path — not pre-emptively.

### 21. Does `Money.ofMajorUnits(BigDecimal)` round correctly for negative amounts (e.g. refunds/adjustments)?

`RoundingMode.HALF_UP` rounds away from zero for the halfway case, so
`-0.005` rounds to `-0.01`, not `0.00` — i.e., it rounds *away* from zero
symmetrically for negative inputs, same magnitude behavior as positive
inputs. This is a deliberately chosen, documented rounding mode (not
`HALF_EVEN`/banker's rounding) — worth being ready to justify: HALF_UP is
the intuitive, commonly-expected rounding behavior for end-user-facing
money amounts (what most people mean by "round 0.5 up"), whereas HALF_EVEN
is typically chosen specifically to eliminate statistical bias across *many*
roundings (e.g. in scientific/accounting aggregate contexts) — not a clear
win for a small number of individual user-facing currency conversions.
