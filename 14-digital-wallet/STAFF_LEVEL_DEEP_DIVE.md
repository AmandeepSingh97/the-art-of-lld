# Staff-Level Deep Dive — Digital Wallet

Hard, problem-specific questions a staff/principal-level interviewer could
reasonably ask after seeing the design in README.md, with reasoning-based
answers (not one-liners). These deliberately challenge the design directly —
some don't have a clean answer, and the strength of the response is in how
honestly the trade-off is argued.

---

### Q1. Two threads submit a transfer on the exact same two accounts, in opposite directions, at the exact same instant. Walk me through what actually happens at the instruction level.

Both threads call `postTwoEntryMovement`. Both independently compute
`lockFirst`/`lockSecond` by comparing `accountId` strings — since this
comparison is a pure function of the two (identical) account ids, both threads
compute the *same* `lockFirst`. Whichever thread's `lockFirst.lock().lock()`
call wins the race (JVM/OS scheduling, not something we control or need to)
proceeds to acquire `lockSecond` as well (uncontended, since the loser hasn't
even requested it yet), completes its entire critical section — debit, credit,
transaction write, two ledger writes — and releases both locks in its
`finally` blocks. Only then does the losing thread's blocked
`lockFirst.lock().lock()` call return, and it repeats the same sequence. The
two transfers are fully serialized with respect to each other (as they must
be — they share both accounts) but neither thread ever blocks indefinitely,
and there's no window where one thread could observe the other's half-applied
state, because the second lock is acquired before any mutation happens.

---

### Q2. What happens if the process crashes between the debit and the credit in `postTwoEntryMovement`?

In the in-memory design: nothing can crash "between" them in the sense of a
process-level failure leaving one applied and not the other, because there is
no I/O or yield point between those two lines — it's pure in-JVM-heap mutation
on two `Account` objects, and a process crash takes down the entire heap
atomically (there's no partial-heap-crash). The real version of this question
is about a **database-backed** implementation: there, the debit and credit
must be two statements inside **one** SQL transaction, so a crash before
`COMMIT` leaves neither applied (the DB's WAL/undo log rolls it back on
restart) — the atomicity guarantee shifts from "single JVM, no crash window"
to "database ACID transaction," but the *shape* of the guarantee (all-or-
nothing) is identical. This is exactly why section 12 of the README specifies
both updates inside one `BEGIN...COMMIT` block rather than two auto-committed
statements.

---

### Q3. Your lock ordering is "ascending accountId." What if `accountId` generation changes later (e.g. switching from UUID strings to auto-incrementing longs) — does the deadlock-freedom property survive?

Yes, as long as the new id type still has a **total, consistent order** that
every thread computes identically (longs compare `<`/`>` just as reliably as
strings compare lexicographically) and as long as *all* code paths that lock
two accounts (currently just `postTwoEntryMovement`) go through the same
ordering function. The actual risk isn't the id type — it's a *future*
third code path that locks two accounts using some other criterion (e.g.
"lock the smaller-balance account first" — a reasonable-sounding "optimization"
that silently breaks the global-order guarantee because it's not consistent
across calls with different initial balances). The real engineering practice
here is: the lock-ordering function should be a single, named, tested utility
(not reimplemented at each call site) precisely so nobody "optimizes" it
differently in a second call site later.

---

### Q4. A new requirement arrives: "support a 3-way split payment — one account pays, two accounts receive." Does your design need to change?

The core locking primitive doesn't generalize for free — `postTwoEntryMovement`
is hard-coded to exactly two accounts. A 3-way split needs either (a) compose
it as two sequential two-account transfers (same source, two destinations,
two separate `Transaction`/idempotency keys) — simplest, but means the split
isn't atomic as *one* operation (a crash after leg 1 leaves a half-split state,
recoverable only by the caller noticing and reversing leg 1), or (b)
generalize the lock-ordering function to take a *list* of N accounts, sort
them all by id, acquire all N locks in that sorted order, and write N ledger
entries (1 debit, N-1 credits) atomically as one `Transaction`. (b) is the
"adding a requirement without rewriting the core" answer: the sort-then-
acquire-in-order technique generalizes cleanly from 2 to N locks — it's the
same Coffman-circular-wait argument, just over a longer ordered list. This is
a good moment to show you understand *why* the 2-account special case
generalizes rather than needing a fundamentally different algorithm.

---

### Q5. You chose pessimistic locking over optimistic (version-based) locking. Defend that choice specifically for a payments wallet, not in the abstract.

A popular wallet account (say, a merchant receiving thousands of incoming
payments per minute) is a **high-contention** resource by construction — many
independent payers debit their own accounts but all credit the *same*
destination account. Optimistic locking (read version, compute new state,
`UPDATE ... WHERE version = :read_version`, retry on 0-rows-affected) degrades
badly exactly under high contention: with enough concurrent writers to one
row, the retry rate can approach "nobody ever wins on the first try," wasting
CPU and increasing tail latency pathologically (this is the classic
optimistic-locking livelock failure mode). Pessimistic locking's cost (threads
block instead of retry) is actually *better* behaved under this exact load
shape — blocked threads don't burn CPU, and the queue drains in FIFO-ish order
under `ReentrantLock`'s default (non-fair) mode, or strictly FIFO if
`ReentrantLock(true)` fairness is enabled (a real, available escalation if
starvation became a measured problem). The honest caveat: if most of your
accounts are *not* hot (the common case — a retail user transacts a handful of
times a day), optimistic locking would work fine for ordinary load, and only
truly hot accounts need special handling — a production system could in
principle use optimistic locking as the default and route known hot accounts
through dedicated serialized processing, a hybrid worth naming even though
this design doesn't build it.

---

### Q6. What's the actual first thing that breaks at 10 million users?

Not the locking logic (per-account locks scale horizontally with the number of
accounts — 10M independent accounts means 10M independent lockable resources,
not one bottleneck). The first thing that breaks is **the single in-memory
process's heap and the single-instance `IdempotencyStore`/repositories**: you
simply can't hold 10M accounts' worth of balances, ledger histories, and an
ever-growing idempotency cache in one JVM's heap, and you can't run only one
instance of the service for availability/throughput reasons at that scale
either. That forces (a) a real database (sections 12/17), and (b) multiple
service instances behind a load balancer, which in turn forces the
idempotency store to be **shared and durable** (Redis/DB, not a process-local
map — two different instances handling the client's original request and its
retry must agree on whether it already happened). The *design* (lock ordering,
double-entry, idempotency-as-a-wrapper) survives unchanged; only the
*implementations* behind the repository/idempotency interfaces need to change
— which is the entire point of having those interfaces.

---

### Q7. Second thing that breaks: what about one specific account getting hit extremely hard (a viral merchant)?

Per-account locking means that *account's* lock becomes a hard serialization
point no matter how many shards/instances you have — if every write to that
account must go through one logical lock (or one DB row lock), you have an
upper bound on that account's throughput regardless of horizontal scaling
elsewhere. Real-world mitigations (worth naming, not building): (a) **balance
sharding/sub-accounts** — split one "hot" logical account into N physical
sub-balances that are debited/credited independently and reconciled
periodically, trading perfect real-time consistency for throughput (this is
how some high-throughput ledgers handle a single "firehose" account); (b)
**write-behind batching** — queue credits to the hot account and apply them in
batches rather than one lock-acquisition per incoming payment, trading
latency-per-payment for much higher aggregate throughput. Both are explicitly
production-evolution answers — this design's lock-per-account strategy is
correct but not infinitely scalable for a single pathological account, and
saying so unprompted is a stronger signal than waiting to be asked.

---

### Q8. Your idempotency store caches by key in a plain map, keyed only on the string. What if a client reuses the same idempotency key for a *different* request body (e.g. same key, different amount)?

This is a real bug class in the current design: `computeIfAbsent` only looks
at the key, not the request content, so a client bug that reuses key `K` for
transfer of $10 and later (legitimately, in its own mind) for a *different*
transfer of $50 using the same key would silently get back the **first**
result (the $10 transaction) for the second request — a correctness bug
disguised as "working as designed." The fix: the idempotency record should
store a hash of the request payload alongside the result, and a request with
the same key but a **different** payload hash should be rejected outright
(HTTP 409/422, "idempotency key reused with different parameters") rather than
silently returning stale cached data. This is a known real-world API design
pattern (Stripe's idempotency keys work exactly this way) and is a
legitimate, named gap in the current implementation worth calling out rather
than leaving implicit.

---

### Q9. Why is `Transaction` immutable with a `withStatus` method instead of just having a mutable `status` field with a setter?

Two reasons, both load-bearing. First, **thread-safety for free**: an
immutable object can be freely handed to any thread (e.g. returned in a
`TransferResult` to a caller, while simultaneously another thread later
reverses it and the repository holds a *different* object instance with
`REVERSED` status) with zero synchronization, because nobody can mutate it out
from under a reader. A mutable `status` field would need its own
synchronization discipline (volatile at minimum, probably a lock) just to be
read safely from multiple threads — complexity this design avoids entirely by
construction. Second, **auditability**: because the original object is never
mutated, any code that captured a reference to "the transaction as it was at
time T" (e.g. a log line, a cached response) continues to see the true
historical state — "what I returned to the client really was COMPLETED at
that moment," which matters a great deal in a financial audit trail.

---

### Q10. Walk me through what would actually go wrong if you used `synchronized` on the `Account` object instead of a `ReentrantLock` field.

Functionally, for the *current* design, very little — `synchronized(account)`
would give the same mutual exclusion. The reasons `ReentrantLock` was chosen
instead are more about what the design needs to **support later**, not a
correctness gap today: (a) `ReentrantLock` exposes `lock()`/`unlock()` as
ordinary method calls that two *different* pieces of code (the `Account`
itself, calling internally, and `WalletServiceImpl`, calling externally to
hold the lock across a longer critical section spanning two accounts) can
coordinate on without both needing to literally be inside the same
`synchronized(sameObjectRef) { }` block textually — this is exactly what makes
"acquire two accounts' locks, do work spanning both, release both" expressible
at all; `synchronized` blocks must be lexically nested, which makes exactly
this "acquire first, then acquire second, in a loop or conditionally" pattern
awkward. (b) `ReentrantLock` offers `tryLock(timeout)`, which is the natural
tool if the design later wanted to *detect* contention and fail fast or apply
backoff instead of blocking forever — not used today, but available without a
rewrite. (c) `ReentrantLock.isLocked()`/`getHoldCount()` are useful for
diagnostics/metrics (e.g. "how many threads are currently blocked trying to
debit this specific account") that `synchronized` doesn't expose at all.

---

### Q11. Your daily transfer limit resets based on `LocalDate.now(clock)`. What happens at midnight, right at the boundary, under concurrent load?

`resetDailyWindowIfNeeded()` runs **inside** the account's lock, as part of
the same critical section as the debit itself — so there's no window where
two concurrent debits could both observe "today's window hasn't been reset
yet" and both proceed past a stale, higher `dailyTransferredMinorUnits`
total; whichever thread's debit executes first (under the lock) either resets
the window or doesn't, and the next debit sees a fully-consistent post-reset
state. The subtler issue is **which clock, and whose "midnight"**: using
`Clock.systemUTC()` (or any single fixed-timezone clock) means the reset
happens at UTC midnight, not at midnight in the *user's* timezone — a design
decision worth naming explicitly ("the limit resets on a UTC calendar day, not
local time; a production system would likely want the limit tied to the
user's registered timezone or a fixed business-defined cutoff") rather than
silently assuming UTC is obviously correct.

---

### Q12. If I ask you to add a "pending" state for deposits that take time to settle (e.g. a bank ACH transfer that takes 2 business days), what in your design has to change?

`TransactionStatus` already has room for this without an API-breaking change:
it would gain a `PENDING` value (additive enum change, section 17's backward-
compatibility guidance). The harder change is in `Account`: today, `applyCredit`
immediately increases the balance — for a pending deposit, you'd need to
distinguish "available balance" (spendable now) from "pending balance"
(reflected in the account's total but not yet spendable), which means
`Account` needs a second long field (`pendingCreditMinorUnits`) and
`applyDebit`'s funds check needs to check *available*, not total. The
deposit flow becomes two steps: `initiateDeposit` (creates a `PENDING`
transaction, adds to `pendingCreditMinorUnits`, no change to spendable
balance) and a later `settleDeposit(transactionId)` (moves the amount from
pending into the real spendable balance, transaction becomes `COMPLETED`).
This is a real, bounded, and well-isolated change — it doesn't touch the
transfer/reversal logic or the locking strategy at all, which is a good sign
that the original design's boundaries were drawn in the right place.

---

### Q13. Your `getStatement` method takes `(accountId, from, to)` and returns a `List<LedgerEntry>`. What breaks when an account has 50 million ledger entries?

Two things, named honestly: first, `InMemoryLedgerRepository.findByAccountId`
is a linear scan + filter over the account's `CopyOnWriteArrayList` — O(n) in
the *total* number of entries ever written for that account, not O(entries in
the requested window), which is clearly wrong at 50M rows. A real
implementation needs the `(account_id, created_at)` composite index named in
section 12's schema, turning the query into an efficient range scan. Second,
even with an index, returning the *entire* matching window as one in-memory
`List` doesn't scale if the window itself is huge (e.g. "give me this
account's entire 5-year history") — the API should support cursor-based
pagination (`getStatement(accountId, from, to, pageToken, pageSize)`) rather
than ever materializing an unbounded list, a change to the public interface
that's worth flagging as a known limitation of the current signature rather
than silently assuming "statements are always small."

---

### Q14. Why not use a single global `ReentrantReadWriteLock` — reads (balance checks) take the read lock, writes (debit/credit) take the write lock — instead of one exclusive lock per account?

A read-write lock's benefit (concurrent readers) doesn't actually apply to
this problem's *shape*: a debit is never "just a read" — the check
(`balance >= amount`) and the write (`balance -= amount`) are one atomic unit
that must exclude *all* other access, reads included, because a concurrent
"pure read" happening between the check and the write would be reading a
balance that's about to become stale the instant the write lock is granted,
which is fine for a non-authoritative dashboard read (and indeed,
`getBalanceSnapshot()` deliberately doesn't take any lock at all, for exactly
that use case) but provides no benefit for the actual transfer logic, which
never has a "read-only" critical section to begin with. A read-write lock
would be the right tool if there were a genuinely expensive, frequent,
read-only operation that needed to run inside the same lock as writes (e.g. if
`getStatement` needed to be lock-consistent with in-flight writes) — but
`getStatement` deliberately reads from the separate `LedgerRepository`, not
through the `Account` lock, specifically to avoid needing one.

---

### Q15. Your reversal releases the daily-limit reservation on the *originally debited* account. What if that account has since been closed?

Looking at `releaseDailyLimitReservation`'s implementation:
`accountRepository.findById(accountId).ifPresent(a -> a.releaseDailyLimitReservation(amount))`
— it's a best-effort `ifPresent`, so if the account somehow doesn't exist
anymore (not currently possible, since accounts are never deleted, only
`CLOSED`, in this design) the release silently no-ops rather than throwing.
For a `CLOSED` (but still present) account, the release *does* still execute —
on reflection this is actually correct behavior: releasing a daily-limit
reservation on a closed account is harmless bookkeeping (nobody will ever debit
that account again to make use of the freed-up headroom), and failing the
whole reversal because the account is closed would be the wrong call — the
reversal's *primary* job is restoring the money via the two-entry movement,
and the daily-limit release is a secondary consistency nicety that shouldn't
block the primary guarantee. Worth stating this reasoning explicitly if asked,
rather than treating the `ifPresent` as an oversight.

---

### Q16. What's the actual blast radius if `IdempotencyStore.computeIfAbsent`'s underlying `ConcurrentHashMap` bin has a hash collision with a totally unrelated key?

None, correctness-wise — `ConcurrentHashMap` resolves collisions correctly
(chained buckets / tree-ified bins at high load), so two different
idempotency keys landing in the same internal bin never cause one key's
computation to block or interfere with another key's *result*, only
(potentially) contend briefly on the same internal segment lock during
concurrent `computeIfAbsent` calls to *different* keys that happen to hash
into the same bin — a performance footnote, not a correctness one. This is
worth distinguishing clearly if asked, because "hash collision" sounds
alarming but `ConcurrentHashMap`'s whole design point is making collisions a
non-issue for correctness.

---

### Q17. If I told you to ship this in 2 weeks with a 2-person team, what would you cut first, and what would you absolutely refuse to cut?

Cut first, in order: (1) daily KYC limits — real compliance systems are
separate services anyway, a wallet MVP can launch without self-enforcing this
and add it before handling real regulated volume; (2) reversal/refund flow —
painful to ship without, but survivable for 2 weeks via a manual
support-driven process (a human adjusts balances through an admin tool) while
the real feature is built; (3) account freeze — same reasoning, can be a
manual DB flag initially. **Absolutely refuse to cut**: the overdraft
invariant and the idempotency-on-every-mutating-call guarantee — these are
the two things that, if missing, cause *silent financial loss* (overdraft:
money created from nothing; missing idempotency: a client retry double-moves
real money) rather than a visibly broken feature a user complains about and
you fix next sprint. The difference between "feature missing" and "money
silently wrong" is exactly the line a staff engineer should be drawing when
asked to cut scope under a deadline.

---

### Q18. Your double-entry invariant is checked by a unit test, not enforced structurally at the type level. Could a future engineer accidentally write a code path that breaks it?

Yes, and this is a legitimate design gap worth naming rather than hiding.
Today, the invariant ("every `TRANSFER`/`REVERSAL`-of-`TRANSFER` writes exactly
one `DEBIT` and one `CREDIT` `LedgerEntry` for the same `transactionId` and
amount") holds only because `postTwoEntryMovement` is the *sole* code path
that writes two-account ledger entries, and it happens to always write them
correctly — nothing in the type system prevents a future engineer from adding
a new method that writes an unbalanced pair. A stronger structural guarantee
would introduce a `LedgerPosting` type that's constructed only via a factory
enforcing "exactly one `DEBIT` + one `CREDIT`, equal amount" at construction
time (impossible to even instantiate an unbalanced posting), and have
`LedgerRepository.append` take a `LedgerPosting`, not a raw `LedgerEntry` —
pushing the invariant into the type system instead of relying on "there's only
one call site today." This wasn't built in the current design to avoid
over-engineering a two-method codebase, but it's exactly the kind of
refactor I'd recommend the moment a second call site needs to write ledger
entries.

---

### Q19. How would you detect, in production, that the overdraft invariant had somehow been violated (e.g. due to a bug you haven't found yet)?

Two independent detection mechanisms, deliberately redundant with each other:
(1) a scheduled reconciliation job that, for every account, independently sums
`ledger_entry.signed_amount` and compares it to `account.balance_minor` —
any drift is itself evidence of a bug (a write that updated one but not the
other, or updated them inconsistently) and should page someone immediately,
*before* a customer notices; (2) a database-level `CHECK (balance_minor >= 0)`
constraint (already in the section 12 schema) that makes an overdraft
*impossible to even persist*, turning a would-be silent corruption into a
loud, immediate transaction failure and a stack trace to investigate, rather
than a quietly-wrong number sitting in the database until the reconciliation
job runs hours later. Neither mechanism alone is sufficient — the DB
constraint catches corruption at write-time but says nothing about *logical*
errors that keep the invariant technically non-negative but still wrong (e.g.
crediting the wrong account); the reconciliation job catches those, but only
on its next scheduled run.

---

### Q20. Your in-memory `InMemoryIdempotencyStore` has no eviction. In a long-running test or demo process, does this actually matter, and when would it start to matter?

In a demo/test process (seconds to minutes of runtime, hundreds of keys), no
— it's a non-issue. It starts to matter the moment the process is meant to run
continuously in production: every unique idempotency key a client has ever
used is retained forever, so memory grows unboundedly with traffic volume,
eventually causing OOM. The fix (TTL-based eviction, e.g. keys older than 24
hours — chosen to comfortably exceed any realistic client retry window) is
simple in principle but has a subtlety worth naming: you must not evict a key
*while* a client might still legitimately retry with it, or you'd lose the
dedup guarantee for a late retry and risk a double-apply — so the TTL has to
be a deliberately chosen business decision (usually driven by the client's own
retry/backoff policy and timeout settings), not an arbitrary implementation
convenience.

---

### Q21. Why does `Account.applyCredit` allow crediting a `FROZEN` account but `applyDebit` doesn't, when both are described as "mutations"? Isn't that an inconsistent rule?

It's an intentional asymmetry, not an oversight, and it's worth being able to
justify the asymmetry rather than just stating it. A freeze is typically a
compliance/fraud *hold on outbound movement* — the regulatory/business concern
is almost always "stop this account from moving money *out*" (laundering,
fraud, a dispute in progress), not "stop other people from paying this
person." Blocking inbound credits during a freeze would actively make the
situation worse in most real scenarios (e.g. a merchant's account frozen
pending review still needs to receive legitimate customer payments that
accrue while the review is in progress — bouncing them would cause customer-
facing failures for a problem that's about to be resolved one way or the
other). The one case where this asymmetry is wrong is a *closed* account —
which is why `applyCredit` *does* reject `CLOSED` (but not `FROZEN`) — a
closed account has no owner expecting money, so a credit to it is almost
certainly a routing error that should fail loudly rather than silently
succeed into a dead account.

---

### Q22. If a reviewer told you "I don't trust that your two-account lock-ordering test actually proves deadlock-freedom — it just proves it didn't deadlock *this time*," how do you respond?

That's a fair and important challenge, and the honest answer is: a passing
test under a timeout is **evidence**, not a **proof** — it demonstrates the
code didn't deadlock in this particular run, with this particular thread
interleaving, which is inherently non-exhaustive for a concurrency bug (the
classic "it passed 10,000 times in CI and deadlocked once in production" risk
for genuinely racy code). The actual *proof* of deadlock-freedom is the
argument in README.md section 11.4 — a structural one, not an empirical one:
"all call sites that acquire two locks go through one function, which
computes the same total order over the same two accounts regardless of
caller," which is a *static, inspectable* property of the code (one function,
one ordering rule, no other code path acquires two account locks) rather than
something you could ever fully verify by running the code enough times. The
test's actual job is narrower and still valuable: it catches a *regression* —
if someone later "optimizes" the lock-ordering logic and breaks the global-
order guarantee, the test is very likely (though, again, not provably
certain) to hang and fail under timeout, which is a useful safety net on top
of, not a replacement for, the structural argument.

---

### Q23. What's actually different about reversing a `TRANSFER` versus reversing a `DEPOSIT`, structurally in your code, and why?

A `TRANSFER` reversal is a two-account movement (money moves from the
originally-credited account back to the originally-debited account), so it
goes through `postTwoEntryMovement` — the same deadlock-free, dual-locked
path as any transfer. A `DEPOSIT` reversal is fundamentally a *single-account*
movement (the wallet account that received the deposit is debited; there's no
second wallet account on the other side — the other leg is the external bank
rail, which this ledger doesn't model), so it goes through
`doSingleEntryMovement`, which only ever touches one account's lock. This
distinction matters for the deadlock argument: you only need the ascending-id
lock-ordering discipline when a code path touches *two* accounts
simultaneously; a correct design doesn't apply unnecessary locking ceremony
(e.g. "lock two things" machinery) to a case that structurally only ever
needs one lock — doing so would add complexity and a (trivial but real)
performance cost with zero safety benefit.
