# Follow-Up Questions — Digital Wallet

25+ realistic interviewer follow-ups, categorized, each with a full answer.

---

## Object-Oriented Design

**Q1. Why is `Account` a `final` class instead of designed for subclassing
(e.g. `SavingsAccount extends Account`)?**
There's no behavioral variation identified in the requirements that would need
polymorphism over `Account` — every account in this domain behaves identically
(same debit/credit rules, same lock discipline). Introducing a subclass
hierarchy without a concrete behavioral difference to justify it would be
speculative generality. If a real "savings account with interest accrual"
requirement appeared, the better fit is composition (an `InterestPolicy`
strategy object `Account` holds a reference to) rather than inheritance,
since interest accrual is a *capability* an account can have, not an *is-a*
relationship that changes how debit/credit work.

**Q2. Why does `Money` implement `Comparable<Money>` instead of just exposing
getters and letting callers compare `minorUnits()` directly?**
`Comparable` lets `Money` be used directly in sorted collections, `Stream.sorted()`,
or threshold checks (`amount.compareTo(limit) > 0`) without every caller
needing to know (or remember to check) that comparison requires matching
currencies first — that invariant is enforced once, inside `compareTo`, rather
than at every call site.

**Q3. Why does `TransferRequest` exist as a separate type instead of just
passing `(sourceId, destId, amount, key, description)` as five parameters to
`transfer()`?**
Five same-typed-ish parameters (several `String`s) in a row is a classic
"parameter clumping" smell — easy to accidentally transpose two `String`
arguments at a call site with no compiler help. Bundling them into
`TransferRequest` gives the compiler a single, named, harder-to-misuse
argument, and gives the API room to grow (e.g. adding a `feeAmount` field)
without changing every call site's parameter list/order.

**Q4. Is `WalletService` an interface because you genuinely expect multiple
implementations, or just "because interfaces are good practice"?**
Genuinely for multiple implementations, and this is worth being able to name
concretely rather than hand-waved: (a) `WalletServiceImpl` for production,
(b) a test double / in-memory fake for testing code that *depends on*
`WalletService` without exercising the real locking logic, (c) potentially a
decorator (e.g. `MeteredWalletService implements WalletService` wrapping the
real one to add metrics) without touching the core implementation. "Interface
because good practice" with no concrete second implementation in mind is a
weaker justification than naming the actual reasons.

---

## SOLID Principles

**Q5. Where does Single Responsibility actually show up in this design, concretely?**
`Account` is responsible for balance/status/limit invariants and nothing else
— it doesn't know about ledger entries, transactions, or idempotency.
`WalletServiceImpl` is responsible for orchestration (locking order,
idempotency wrapping, persistence sequencing) — it doesn't implement its own
locking primitives or storage. `Money` is responsible only for exact monetary
arithmetic. Each class has exactly one reason to change: `Account` changes if
the balance/limit rules change; `WalletServiceImpl` changes if the
orchestration flow changes; they rarely need to change together.

**Q6. Where's Open/Closed in this design?**
Adding a new `TransactionType` (e.g. `FEE`) or a new `IdempotencyStore`
backend (Redis instead of in-memory) requires no changes to existing, working
code — `WalletServiceImpl`'s logic is written against the `IdempotencyStore`
interface and a `switch` over `TransactionType` with an explicit `default`
branch (in `doReverse`) that fails loudly rather than silently mishandling an
unknown case. New enum values are additive; existing call sites don't need to
be touched (though any `switch` over the enum without a `default` *would*
need a new case added — a deliberate, compiler-enforced reminder rather than
a silent gap).

**Q7. Liskov Substitution — is it actually at risk anywhere here?**
The main place it could bite is `IdempotencyStore`: `InMemoryIdempotencyStore`
promises "computation never re-runs for a cached key." A hypothetical
`FlakyNetworkIdempotencyStore` that sometimes re-ran the computation on a
network hiccup would violate the interface's behavioral contract even while
matching its method signature — a classic LSP violation that signatures alone
don't catch. This is why the `IdempotencyStore` javadoc states the atomicity
contract explicitly in prose, not just the method signature — any
implementation must honor it to be substitutable.

**Q8. Interface Segregation — why not one big `WalletRepository` instead of
three separate repository interfaces?**
A combined interface would force every implementation (and every test fake) to
implement account, transaction, *and* ledger persistence even if a given
context only needs one (e.g. a test that only cares about account balances
shouldn't need to stub ledger methods). Three narrow interfaces mean
`WalletServiceImpl`'s dependencies are precisely what it uses, and test doubles
stay minimal.

**Q9. Dependency Inversion — show me the actual inversion, not just "we used interfaces."**
`WalletServiceImpl` (a high-level policy module — business rules) depends on
`AccountRepository`/`TransactionRepository`/`LedgerRepository`/`IdempotencyStore`
(abstractions), not on `InMemoryAccountRepository` etc. (low-level,
implementation-detail modules) directly. The *low-level* modules depend on the
abstraction too (they implement it). Concretely: if tomorrow
`InMemoryAccountRepository` is deleted and replaced by `JdbcAccountRepository`,
zero lines in `WalletServiceImpl` change — the dependency arrow points from
both sides *toward* the interface, which is the actual definition of the
inversion, not merely "there exists an interface somewhere."

---

## Design Patterns

**Q10. You used Builder for `Transaction`. Why not a simple all-args constructor
with `null` for the fields that don't apply (e.g. `sourceAccountId` for a deposit)?**
An all-args constructor with positional `null`s is exactly the kind of code
that causes bugs at 2am — nothing stops a future caller from passing the
arguments in the wrong order, or forgetting which positional `null` means
"this is a deposit" vs "this is a withdrawal." The builder's named methods
(`.sourceAccountId(x)`, `.destinationAccountId(y)`) make the omission of a
field self-documenting at the call site.

**Q11. Repository pattern — isn't this just a re-implementation of DAO (Data
Access Object)? What's the actual difference you're relying on?**
In practice, very similar; the distinction some literature draws is that
Repository speaks in terms of the *domain model's* collection-like semantics
(`save`, `findById` — "pretend this is an in-memory collection of aggregates")
while DAO is more explicitly about *data access operations* tied to a storage
technology. For this design, the practical point that matters is the same
either way: the interface boundary is what lets `WalletServiceImpl` be written
and tested without knowing or caring whether the implementation is a
`ConcurrentHashMap` or a JDBC `PreparedStatement`.

**Q12. Why didn't you use the Strategy pattern for the daily-limit check (e.g.
a pluggable `LimitPolicy` interface) given you already use it for idempotency?**
Fair question — it's a real candidate for future extensibility (e.g. if
different account *types*, not just tiers, needed fundamentally different
limit logic, like a rolling 30-day window instead of calendar-day). It wasn't
introduced because, today, there's exactly one limit policy (a fixed
per-calendar-day cap keyed off an enum), and introducing a `Strategy` interface
for a single, simple, unlikely-to-vary rule would be exactly the kind of
over-engineering the design standards call out — Strategy earns its place when
there's a genuine, anticipated need for runtime-swappable behavior, which
`IdempotencyStore` has (in-memory vs Redis is a real, near-term swap) and the
limit policy, as currently scoped, does not.

---

## Data Structures

**Q13. Why `ConcurrentHashMap` and not a plain `HashMap` guarded by a single
`synchronized` block, for the account repository?**
A single `synchronized` block around a `HashMap` serializes *every* account
lookup/insertion system-wide behind one lock, even though looking up account A
and looking up account B have no actual data dependency on each other.
`ConcurrentHashMap` achieves thread-safety with much finer-grained internal
locking (historically per-segment, now largely per-bin with CAS-based fast
paths), so unrelated lookups barely contend at all — a meaningful difference
under real concurrent load, for a data structure this design relies on
constantly (every single operation does at least one account lookup).

**Q14. Why `CopyOnWriteArrayList` for ledger entries instead of just a
`synchronized` `ArrayList`, given you said appends are relatively rare?**
Both are correct; the choice is about where the cost is paid. `CopyOnWriteArrayList`
pays an O(n) copy cost *only on write* (append), and reads (iteration for a
statement) are then entirely lock-free and never block on a concurrent
append. A `synchronized ArrayList` pays a small, constant lock-acquisition
cost on *every* operation, read or write, and readers can block behind a
writer. Given the stated workload (append-rare, relative to how often a
popular account's statement might be read, e.g. by a balance/activity screen
refreshing), COW's trade-off (expensive rare writes, cheap frequent lock-free
reads) fits better. The crossover point where COW stops being a good choice is
exactly where this README names it: if a single account accumulates millions
of entries, the O(n) append cost itself becomes the bottleneck, independent of
concurrency — at that point you'd move to a different structure entirely
(section 14).

**Q15. Why not a `TreeMap`/sorted structure for ledger entries if you need
chronological order for a statement?**
Entries are already appended in chronological order (the system clock only
moves forward, and appends happen sequentially under each account's lock), so
the list is *already* sorted by insertion order — no need to pay for
comparison-based ordering (`O(log n)` insert) when append-order (`O(1)` amortized
insert, ignoring COW's copy cost) already gives you the order you need. A sorted
structure would only earn its cost if entries could arrive out of order
(e.g. backdated corrections), which this design doesn't support.

---

## Java Internals

**Q16. Explain exactly why `long balanceMinorUnits` doesn't need to be
`synchronized` to read atomically, separate from the question of whether
reading a *stale* value is OK.**
The Java Language Specification guarantees that reads and writes of `long`
and `double` fields declared `volatile` are atomic — no thread can ever observe
a "torn" value (half of one writer's bits mixed with half of another's),
which *non-volatile* 64-bit fields are technically not guaranteed to avoid on
some older/32-bit JVMs (a well-known JLS footnote). Declaring the field
`volatile` here buys exactly that atomicity-of-read-the-whole-64-bits
guarantee plus the separate (also important) visibility guarantee — a write
by one thread is guaranteed visible to a subsequent read by another thread
without any additional synchronization. Neither guarantee, on its own, makes
compound operations (check-then-act) safe — that's still the lock's job — but
both are necessary for even a simple unguarded read to be meaningful at all.

**Q17. Why does `ReentrantLock` need to be reentrant for this design
specifically — show me the actual call path that re-enters it.**
`WalletServiceImpl.postTwoEntryMovement` acquires `debitAccount.lock()`
explicitly, then calls `debitAccount.applyDebit(...)`, which *itself* also
calls `lock.lock()` internally (so `Account`'s own methods are safe even if
called standalone, outside any orchestration). Since both calls happen on the
same thread, `ReentrantLock` tracks a hold count of 2 and lets the second
`lock()` call succeed immediately rather than deadlocking the thread against
itself (which a non-reentrant mutex would do) — the thread must then call
`unlock()` twice (once inside `applyDebit`'s `finally`, once inside
`postTwoEntryMovement`'s `finally`) before the lock is actually released to
other threads, and both `finally` blocks correctly do exactly that.

**Q18. What does `Math.addExact`/`subtractExact`/`negateExact` in `Money`
actually protect against, and what would happen without them?**
They throw `ArithmeticException` on signed 64-bit overflow instead of silently
wrapping around (e.g. `Long.MAX_VALUE + 1` silently becomes
`Long.MIN_VALUE` with plain `+`). For a financial system, silent integer
overflow turning a very large positive balance into a very large *negative*
one is a far worse failure mode than a loud exception — the exception at
least fails safely and visibly; silent wraparound would look exactly like
real (catastrophic) data corruption with no stack trace to investigate.

**Q19. Why does `Transaction` not override `equals`/`hashCode`, while `Money`
and `Account` do (or, in `Account`'s case, `equals`/`hashCode` by id)?**
`Transaction` objects are compared by **identity of meaning**, not value —
two `Transaction` objects with identical field values but different
`transactionId`s represent two genuinely different events (even "$10 deposit,
same second, same description" could legitimately be two separate real
transactions), so relying on default `Object.equals` (identity) is actually
*correct* here, not an oversight — there's no sensible "value equality" notion
for a transaction beyond its id, and the id is already available for
callers that need to compare "are these the same transaction" explicitly via
`transactionId().equals(...)`. `Money` is a true value object (two `$10 USD`s
are interchangeable) so value-based `equals`/`hashCode` is correct there.
`Account` overrides `equals`/`hashCode` by `accountId` specifically (not by
mutable state) so it can be safely used as a map key or in a `Set` even while
its balance is actively changing underneath — hashing by a mutable field would
be a classic "object changed hash bucket after insertion" bug.

---

## Multithreading

**Q20. What's the difference between the daily-limit check being inside
`applyDebit`'s lock versus being a separate method called before `applyDebit`?**
If the limit check were a separate method/call (e.g.
`if (account.wouldExceedLimit(amount)) throw ...; account.applyDebit(amount);`),
two threads could both call `wouldExceedLimit` concurrently, both see "I have
room," and both then call `applyDebit` — the limit check and the balance
mutation are no longer one atomic unit, reintroducing exactly the TOCTOU race
the lock was supposed to prevent. By keeping the limit check *inside*
`applyDebit`, under the same lock acquisition as the balance mutation, it's
impossible for two threads to both pass the check before either one's
debit (and limit-counter increment) is recorded.

**Q21. How would you prove, rather than assert, that your transfer logic has
no lost updates under concurrent load?**
Exactly the property `ConcurrentTransferTest`'s first test checks: run many
concurrent transfers that only move money *between* two accounts (never in or
out of the pair), then assert the **sum** of both balances after the storm
equals the sum before. If any lost update occurred (a write silently
overwritten by a racing write, which is the textbook symptom of missing or
incorrect locking), the total would drift from the expected value — money
would appear to vanish or be duplicated. Conservation-of-total is a strong,
simple, and directly testable proxy for "no lost updates," stronger than
checking any one account's final balance in isolation (which could coincidentally
land on the right number even with a bug, depending on timing).

**Q22. If you needed this to run across multiple JVM instances (not just
multiple threads in one JVM), what would have to change about the locking?**
In-process `ReentrantLock` only coordinates threads *within one JVM* — a
second instance has its own separate lock objects and would happily let its
threads race against the first instance's threads on what's supposed to be
the same logical account. The fix requires a **distributed lock** (e.g. a
database row lock via `SELECT ... FOR UPDATE`, which is already how section 12
describes the production persistence design, or a distributed lock service
like a Redis-based lock/Zookeeper/etc. if not backed by a transactional DB).
The *lock-ordering argument* (always acquire in ascending accountId order)
carries over unchanged — it's the *mechanism* (in-process mutex vs. DB row
lock vs. distributed lock service) that has to change, not the deadlock-
avoidance *strategy*.

---

## Database and Persistence

**Q23. Why `SELECT ... FOR UPDATE` and not `SELECT ... FOR UPDATE SKIP LOCKED`
for the production row-locking design?**
`SKIP LOCKED` is for workloads where a worker should move on to a *different*
row if the one it wants is already locked (e.g. a job queue where any
available job will do). A transfer has no such substitutability — it needs
*this specific* account's row, not "any available account" — so skipping a
locked row would be wrong; the correct behavior when another transaction holds
the row lock is to **wait** for it (ordinary `FOR UPDATE` blocking semantics),
which is exactly what the in-process `ReentrantLock.lock()` (blocking, not
`tryLock`) mirrors.

**Q24. Your schema has a `UNIQUE KEY` on `idempotency_key` in the `transaction`
table. Why is that a *stronger* guarantee than the application-level
`ConcurrentHashMap#computeIfAbsent` approach?**
The unique constraint is enforced by the database itself, independent of
which application process or thread is doing the inserting — two different
service *instances* (not just two threads in one process) racing to insert a
row with the same idempotency key will have exactly one `INSERT` succeed and
the other fail with a unique-constraint-violation error, which the
application then catches and treats as "go fetch the existing row instead."
`ConcurrentHashMap#computeIfAbsent`'s guarantee is scoped to *one JVM's*
heap — it says nothing about two separate processes/instances, which is
precisely why the production (multi-instance) design in section 17 needs the
DB constraint (or a Redis equivalent), not just "the same in-memory map, but
bigger."

**Q25. Why `READ COMMITTED` isolation and not `SERIALIZABLE`, given this is
money?**
`SERIALIZABLE` would prevent anomalies this design doesn't actually need
protection from at the isolation-level layer, because the specific anomaly
that matters (overdraft from a lost update) is *already* prevented
structurally by the explicit `SELECT ... FOR UPDATE` row locks (acquired in a
fixed order) plus the `WHERE balance_minor >= :amount` guard on the `UPDATE`
itself — the row lock makes the read-then-write sequence atomic with respect
to other writers regardless of isolation level, and the `WHERE` clause makes
the invariant self-enforcing even if some other anomaly slipped through.
`SERIALIZABLE` would add real overhead (more aggressive locking/conflict
detection, more transaction retries/aborts under contention) to protect
against anomalies that aren't the ones actually threatening this design's
invariants — using it here would be over-engineering the isolation level
without a corresponding gap it's needed to close.

---

## Performance

**Q26. What's the Big-O of a single `transfer()` call, precisely, and what
dominates it in practice?**
O(1) amortized: two map lookups (`O(1)` average for `ConcurrentHashMap#get`),
two lock acquisitions (`O(1)` uncontended; proportional to queue depth if
contended, which is a runtime/scheduling cost, not an algorithmic one), two
field mutations, one map insert for the transaction, and two list appends for
ledger entries (`O(1)` amortized for `ConcurrentHashMap#put`; `O(n)` *only* for
the `CopyOnWriteArrayList` append itself, where `n` is that specific account's
*existing* ledger entry count — this is the one place the operation isn't
strictly O(1) regardless of account history size, and it's called out
explicitly in sections 10 and 14 as the one structural scalability caveat in
the whole operation).

**Q27. Does adding more CPU cores help this system's throughput, and under
what condition does it stop helping?**
Yes, up to the point where most in-flight transfers involve *disjoint* pairs
of accounts — more cores means more such disjoint transfers can genuinely run
in parallel, since each one only contends for its own 1-2 account locks.
It stops helping once the workload's "hot set" (accounts frequently involved in
transfers) becomes small relative to total throughput demand — at that point,
contention on the hot accounts' locks becomes the bottleneck and additional
cores sit idle waiting for those specific locks, regardless of how many are
available. This is precisely the scenario discussed in STAFF_LEVEL_DEEP_DIVE.md
Q7 (the viral-merchant-account case).

---

## Failure Handling

**Q28. Your `doReverse` method's `switch` on `TransactionType` has a `default`
branch that throws `IllegalStateException`. Why not just let it fall through
silently (returning `null`, say)?**
An unhandled `TransactionType` in a `switch` almost always means "a new enum
value was added somewhere and this `switch` wasn't updated to handle it" — a
real, if hopefully rare, class of bug. Returning `null` or silently doing
nothing would convert that bug into a much harder-to-diagnose downstream
`NullPointerException` (or worse, a reversal that silently does nothing while
reporting success) far from its actual root cause. Throwing immediately, with
a message naming exactly which case was missed, makes this exact bug trivial
to diagnose the first time it's hit, rather than a mystery ticket weeks later.

**Q29. What happens if `ledgerRepository.append()` throws (e.g. an I/O error
in a real database-backed implementation) after the account balances have
already been mutated in memory, but the operation hasn't yet returned?**
In the current in-memory design, `ledgerRepository.append` can't realistically
fail (it's a map/list operation), so this scenario doesn't actually arise —
worth stating plainly rather than inventing a fake failure mode. In a
real **database-backed** implementation, this risk is exactly why section 12
specifies the balance updates and the ledger inserts as statements inside
**one** SQL transaction: if the ledger insert fails for any reason, the whole
transaction (including the balance `UPDATE`s) rolls back together, so there's
no window where "balances moved but the ledger doesn't reflect it" can become
a durably persisted inconsistency — the atomicity is the database's job, which
is precisely why the application-level in-memory version doesn't need its own
separate recovery logic for this case.

---

## Production Architecture

**Q30. How would you roll out a change to the daily-limit calculation logic
(e.g. switching from a calendar-day window to a rolling 24-hour window)
without downtime or a flag day?**
Add the new behavior behind an explicit, per-account (or per-cohort)
feature flag / configuration field rather than a global code branch — e.g. a
`limitWindowStrategy` field on `KycTier` or `Account` that defaults to the old
behavior for all existing accounts and can be flipped to the new behavior for
a canary cohort first. Because the daily-limit check is already isolated
inside `Account` (not scattered across the codebase), this is a localized
change: the rest of `WalletServiceImpl` never needs to know which window
strategy a given account uses. This is also a good moment to mention
backward-compatible rollout practice generally: ship the new field/logic
dark (default to old behavior) in one deploy, flip it for a canary cohort in a
second step, and only remove the old code path once the new one has been
running cleanly in production for a defined bake period.
