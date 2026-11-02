# Digital Wallet (Paytm/Venmo-style) — Low-Level Design

Staff-level interview prep for designing an in-process digital wallet: accounts,
balances, double-entry ledger transfers, idempotent APIs, and overdraft-safe
concurrency. Code: `src/main/java/lld/digitalwallet/`. Tests: `tests/lld/digitalwallet/`.

---

## 1. Problem Understanding

**Plain explanation.** Design the core engine behind a consumer digital wallet
(think Paytm, Venmo, Cash App, PayPal balance). Users hold a balance in an
account. Money moves in three ways: (a) **external funding** — a bank-linked
top-up (deposit) or cash-out (withdrawal); (b) **internal transfer** — wallet-to-
wallet, e.g. splitting a dinner bill; (c) **reversal/refund** — undoing a
transfer or a deposit/withdrawal, e.g. a merchant dispute or a mis-sent payment.

**Real-world use case.** A user opens the app, sees a balance, sends ₹500 to a
friend. The system must never let two concurrent requests double-spend the same
₹500, must never silently drop a transfer if the client's network call times out
and retries, and must be able to produce a complete, auditable statement of
every rupee that ever moved through an account.

**What the interviewer is evaluating.**
- Can you correctly reason about **money as data** (why `double` is disqualifying).
- Can you design a **correct concurrency model** for a classic "transfer between
  two mutable resources" problem — this is the single most common staff-level
  trap (deadlock from inconsistent lock ordering, or silent overdraft from a
  check-then-act race).
- Do you understand **idempotency** as an API design concern, not an afterthought.
- Do you know **double-entry bookkeeping** well enough to use it as a design
  tool (not just cite it) — every movement has two balanced legs.
- Do you keep the design **interview-sized**: no Kafka, no microservices, no
  distributed consensus for what is fundamentally a single-process LLD problem,
  while still being able to *talk about* how it would evolve (section 17).

**In scope.**
- Account lifecycle (open, freeze/unfreeze), balance as integer minor units.
- Deposit, withdrawal, wallet-to-wallet transfer, reversal of any of the three.
- Idempotency keys on every mutating call.
- Concurrency-safe debit/credit with no overdraft, deadlock-free two-account locking.
- Double-entry ledger + account statement.
- Lightweight KYC tiering / daily transfer limits.

**Out of scope (named explicitly, not silently ignored).**
- Multi-currency conversion / FX rates (single currency per account, transfers
  require matching currencies — stated as an assumption).
- Real payment rails (card networks, ACH, UPI) — `deposit`/`withdraw` model the
  *wallet-side* effect of an external rail settling; the rail integration itself
  is out of scope.
- Distributed/sharded ledger, multi-region replication, event sourcing/CQRS —
  discussed as production evolution (section 17), not implemented.
- Fraud/AML scoring engine — KYC tiers are modeled as a simple enum + daily cap,
  not a real risk engine.
- Authentication/authorization of the caller — assumed handled by an API gateway
  layer above `WalletService`.

---

## 2. Requirement Gathering

### Functional — essential
1. Open a wallet account for a user with a currency and KYC tier.
2. Deposit money into an account (external funding in).
3. Withdraw money from an account (external funding out).
4. Transfer money between two accounts, atomically.
5. Reject a debit that would make a balance negative.
6. Every mutating call accepts a client-supplied idempotency key; a retried
   call with the same key must not be applied twice.
7. Produce an account statement (ordered list of ledger postings).
8. Reverse a previously completed transaction (compensating transaction, not a
   mutation of history).

### Functional — optional / nice-to-have
9. Freeze/unfreeze an account (compliance hold).
10. Daily transfer limit tied to KYC tier.
11. Partial refund (reversing less than the full original amount) — **not
    implemented**; see trade-offs (section 16) for why full-amount-only
    reversal was chosen for the interview scope.
12. Multi-currency accounts with FX conversion.
13. Scheduled/recurring transfers.

### Non-functional
| Dimension | Requirement | Interview-necessary vs production-extension |
|---|---|---|
| **Correctness/Consistency** | No overdraft ever, even under concurrent access. Double-entry invariant (every transfer's debit leg + credit leg net to zero) always holds. | Interview-necessary: this *is* the problem. |
| **Thread-safety** | All balance mutations safe under arbitrary concurrent callers; no lost updates, no deadlock. | Interview-necessary. |
| **Idempotency/Reliability** | Duplicate submissions (client retry) never double-apply a side effect. | Interview-necessary. |
| **Performance** | Single transfer should be O(1) lock acquisitions (2 locks) and O(1) map operations; no global lock across all accounts. | Interview-necessary (discuss big-O of your locking, don't need benchmarks). |
| **Scalability** | Should reason about what breaks at 10M accounts / high TPS, without actually building a distributed system. | Discuss only — production-extension. |
| **Availability** | Single-process demo has no HA story. | Production-extension (discuss failover/replication at a high level). |
| **Extensibility** | Adding a new transaction type (e.g. "fee") or a new idempotency backend should not require touching locking logic. | Interview-necessary: validate via the design, not by building it. |
| **Reliability/Auditability** | Every state change traceable via an append-only ledger. | Interview-necessary. |

---

## 3. Clarifying Questions

*(Answered with the assumption chosen for this implementation, and why the
alternative would change the design — these are the "Clarifying Questions"
required by the spec, resolved here since there's no live interviewer.)*

1. **Is this single-process/in-memory, or must it be durable/distributed?**
   *Assumption: single-process, in-memory, but designed so swapping
   `AccountRepository`/`TransactionRepository`/`LedgerRepository`/`IdempotencyStore`
   for JDBC/Redis-backed implementations requires zero changes to
   `WalletServiceImpl`.* If the interviewer says "assume a real database from
   the start," the locking strategy shifts from in-process `ReentrantLock` to
   `SELECT ... FOR UPDATE` row locks (see section 11) — same lock-ordering
   argument, different lock primitive.

2. **Can two accounts be in different currencies, and must transfers support FX?**
   *Assumption: no — all accounts in this model carry a single `Currency`;
   transfer requires `amount.currency() == source.currency() == destination.currency()`.*
   If FX were required, `Transaction` would need two `Money` amounts (debit-side
   amount, credit-side amount) and an FX-rate/quote concept — a materially
   different (and much larger) domain model.

3. **Is "no overdraft" a hard invariant, or can accounts have a credit line /
   go negative up to a limit?**
   *Assumption: hard invariant, balance ∈ [0, ∞).* A credit-line feature would
   change `Account.applyDebit` to check against `balance + creditLimit` instead
   of `balance` — a small change, but changes what "insufficient funds" means
   everywhere, including reversal semantics.

4. **What happens to a debit's daily-limit reservation if the transfer is later
   reversed — does the user get their daily allowance back?**
   *Assumption: yes — reversal releases the limit reservation on the originally
   debited account*, modeled via `Account.releaseDailyLimitReservation`. The
   alternative (limit consumed permanently regardless of reversal) is a one-line
   change (skip the release call) but is a real product/compliance decision.

5. **Should a frozen account be able to receive money (inbound credits) while
   frozen, or does freeze block both directions?**
   *Assumption: freeze blocks only outbound (debits); inbound credits still
   land*, mirroring how most real wallets treat a compliance hold (you can still
   be paid, you just can't move money out). This is explicitly tested
   (`debitsAreBlockedOnFrozenAccountButCreditsStillLand`).

6. **Is a reversal allowed to be partial (refund less than the full amount)?**
   *Assumption: no, full-amount-only reversal.* Partial refunds need a new
   concept (`remainingReversibleAmount` tracked per transaction) and a different
   invariant ("sum of reversals ≤ original amount") — named as a natural
   follow-up extension, not built, to keep the core design legible.

7. **Does the idempotency key need to survive process restarts / be shared
   across multiple service instances?**
   *Assumption: no for this exercise (process-local `ConcurrentHashMap`), but
   the `IdempotencyStore` interface is written so a Redis- or DB-unique-
   constraint-backed implementation is a drop-in replacement.* This matters a
   lot in production (see section 13/17).

8. **Do we need multiple ledger "books" (e.g. a platform fee account, a
   suspense/clearing account) or just user-to-user transfers?**
   *Assumption: just user wallet accounts for this exercise.* A fee-charging
   transfer would simply be two transfers (user→platform fee account,
   user→recipient) composed by a higher-level use case — the primitive
   (`transfer` between any two accounts) already supports this without change.

---

## 4. Domain Modelling

| Type | Kind | Responsibility |
|---|---|---|
| `Money` | Value object | Exact, currency-aware monetary arithmetic in integer minor units. Immutable. |
| `Account` | Entity (aggregate root) | Owns balance, status, KYC tier, daily-limit counters, and its own lock. The only class allowed to mutate a balance. |
| `AccountStatus` | Enum | `ACTIVE` / `FROZEN` / `CLOSED`. |
| `KycTier` | Enum | Policy knob: daily transfer limit per compliance tier. |
| `Transaction` | Value object (immutable record of intent/outcome) | The client-facing "what was asked for and what happened" record; keyed by idempotency key. |
| `TransactionType` | Enum | `DEPOSIT` / `WITHDRAWAL` / `TRANSFER` / `REVERSAL`. |
| `TransactionStatus` | Enum | `COMPLETED` / `FAILED` / `REVERSED`. |
| `LedgerEntry` | Value object (immutable, append-only) | One side (debit or credit) of a double-entry posting against one account. |
| `EntryDirection` | Enum | `DEBIT` / `CREDIT`. |
| `AccountRepository` / `TransactionRepository` / `LedgerRepository` | Repository (interfaces) | Decouple domain/service logic from storage technology. |
| `InMemory*Repository` | Repository (implementations) | `ConcurrentHashMap`/`CopyOnWriteArrayList`-backed demo storage. |
| `IdempotencyStore` | Domain service (interface) | Exactly-once execution of a side effect per client key. |
| `InMemoryIdempotencyStore` | Domain service (implementation) | `ConcurrentHashMap#computeIfAbsent`-based implementation. |
| `WalletService` | Domain service (interface) | The one public API surface. |
| `WalletServiceImpl` | Domain service (implementation) | Orchestrates locking order, idempotency wrapping, ledger writes, reversal state machine. |
| `TransferRequest` / `TransferResult` | DTOs | API request/response shapes, decoupled from the domain model so the wire format can evolve independently. |

**Composition/association/ownership:**
- `Account` *composes* its `AccountStatus`, `KycTier`, and its `ReentrantLock` —
  none of these have meaning or a lifecycle independent of the account.
- `Account`, `Transaction`, `LedgerEntry` *associate with* `Money` — `Money` is a
  freely-shared, immutable value, not owned by any one entity.
- A `Transaction` is *associated with* (not composed of) its `LedgerEntry` rows:
  the ledger is a separate, independently-queryable append-only store (you can
  ask "give me all ledger entries for account X" without going through any
  particular `Transaction` object).
- `WalletServiceImpl` *depends on* (constructor-injected) the three repository
  interfaces and the `IdempotencyStore` interface — classic Dependency Inversion:
  the service layer is testable and storage-agnostic.

**Factories:** `Account.newAccountId()` / `Transaction.newTransactionId()` are
simple static ID factories (UUID-based); no `AccountFactory` class was
introduced — see section 7 for why a full Factory pattern would be
over-engineering here.

---

## 5. Domain Invariants

Business rules that must **always** hold, and exactly where they are enforced:

1. **No negative balance (no overdraft).** Enforced inside `Account.applyDebit`,
   under the account's own lock, as the very last check before mutating
   `balanceMinorUnits`. This is the single most important invariant in the
   whole system — see section 11 for why it requires a lock rather than a CAS.

2. **Double-entry balance: every transfer's two legs sum to zero.** Enforced
   structurally: `WalletServiceImpl.postTwoEntryMovement` is the *only* code
   path that writes `LedgerEntry` rows for a two-account movement, and it always
   writes exactly one `DEBIT` and one `CREDIT` for the identical `Money` amount,
   inside the same critical section as the balance mutations. Verified by test
   `transferWritesTwoBalancedLedgerEntries`.

3. **Currency consistency.** An account only ever holds one currency; a
   transfer/deposit/withdrawal whose `Money.currency()` doesn't match the
   account's currency is rejected before any mutation
   (`requireMatchingCurrency`), so there is never a partially-applied
   cross-currency corruption.

4. **A transaction's idempotency key maps to at most one executed side
   effect.** Enforced by `IdempotencyStore.computeIfAbsent`'s atomicity — see
   section 11.

5. **A transaction can be reversed at most once, and only from `COMPLETED`.**
   Enforced in `WalletServiceImpl.doReverse`: it checks `original.status() ==
   COMPLETED` and, on success, rewrites the stored transaction (a **new**
   immutable object) with `status = REVERSED`; a second reversal attempt sees
   `REVERSED` and is rejected with `IllegalTransactionStateException`.

6. **Debits never bypass the daily KYC-tier limit.** Enforced inside
   `Account.applyDebit` under the same lock as the balance check — the limit
   check and the balance check and the mutation are one atomic unit, so a
   concurrent debit cannot "sneak in" between checking the limit and consuming it.

7. **Transactions and ledger entries are immutable once written.** `Transaction`
   and `LedgerEntry` have no setters; "changing" a transaction's status
   produces a new object (`withStatus`) that replaces the old one in the
   repository — the old object, if anyone still holds a reference to it (e.g. a
   returned `TransferResult`), is never mutated under them. This is what makes
   the ledger safely auditable even in the presence of concurrent readers.

---

## 6. Architecture and Class Diagram

See `diagrams/component-diagram.md` for the package/component view,
`diagrams/class-diagram.md` for the full class diagram (verified to match the
actual `src/` code), and `diagrams/sequence-diagrams.md` for four key sequence
flows (successful transfer, idempotent replay, deadlock-free concurrent
opposite-direction transfers, and reversal).

**High-level flow (textual summary of the diagrams):** A caller only ever talks
to `WalletService`. `WalletServiceImpl` validates the request, wraps the actual
work in `IdempotencyStore.computeIfAbsent` keyed on the client's idempotency
key, and — for two-account operations — acquires both accounts' locks in a
single global order (ascending `accountId`) before mutating anything. Every
successful mutation writes an immutable `Transaction` plus one or two
`LedgerEntry` rows before releasing the locks, so no other thread can ever
observe an intermediate (half-applied) state.

---

## 7. Design Patterns

| Pattern | Why needed | Where applied | Alternative considered | Why rejected |
|---|---|---|---|---|
| **Repository** | Decouple the service layer from the storage technology so an in-memory demo and a future JDBC implementation share identical business logic. | `AccountRepository`, `TransactionRepository`, `LedgerRepository` + `InMemory*` implementations. | Let `WalletServiceImpl` talk to `ConcurrentHashMap` directly. | Would hard-wire storage technology into business logic, making the "how would this work with a real DB" interview follow-up unanswerable without a rewrite. |
| **Strategy (via interface)** | Allow the idempotency backend to vary (in-memory map today; Redis `SETNX`, or a DB unique constraint, tomorrow) without touching `WalletServiceImpl`. | `IdempotencyStore` interface + `InMemoryIdempotencyStore`. | Make idempotency a `static` utility method. | A static method can't be swapped per-deployment and can't be unit-tested in isolation from the rest of the service. |
| **Builder** | `Transaction` has many fields, several optional (`sourceAccountId` is absent for a pure deposit, `reversalOfTransactionId` is absent unless it's a reversal) — a builder avoids a telescoping/overloaded-constructor mess and keeps the class immutable. | `Transaction.Builder`. | Multiple overloaded constructors, or a mutable JavaBean with setters. | Overloaded constructors for 4 optional combinations is unreadable; a mutable bean would break the immutability invariant (section 5, rule 7) that makes the ledger safely shareable across threads. |
| **Value Object** | Money, ledger entries, and transactions must be immutable and compared by value, not identity, to be safely shared across threads without synchronization. | `Money`, `LedgerEntry`, `Transaction`. | Mutable POJOs with getters/setters. | Mutable shared state is exactly the bug class this design exists to prevent. |
| **Dependency Injection (constructor injection)** | `WalletServiceImpl` must be testable with fakes/in-memory repos without a DI framework. | `WalletServiceImpl`'s constructor takes all four collaborators as interfaces. | A service-locator/singleton registry. | Hides dependencies, makes unit testing harder, and is a well-known anti-pattern for exactly this reason. |

**Patterns deliberately *not* used (and why forcing them would be
over-engineering):**
- **Factory Method / Abstract Factory** for `Account`/`Transaction` creation —
  a single static `newAccountId()`/`newTransactionId()` helper is enough; there
  is only one concrete `Account` class, so a factory hierarchy adds indirection
  with no polymorphism to justify it.
- **Observer/Event Bus** for "transaction completed" notifications — no
  requirement calls for pub/sub inside this exercise; adding one here would be
  solving a problem nobody asked for (named explicitly in section 17 as a
  production extension point, e.g. for emitting Kafka events).
- **Singleton** for the repositories/service — ordinary constructor-injected
  instances are enough; a `WalletService` singleton would make unit testing
  (which needs a *fresh* instance per test) actively harder.
- **Command pattern** for `deposit`/`withdraw`/`transfer` — these are three
  distinct, simple method calls; wrapping each in a `Command` object to get
  undo/redo is unnecessary because reversal is already modeled as its own
  first-class operation (`reverse`), not a generic command-undo mechanism.

---

## 8. API and Method Design

Public interface: `lld.digitalwallet.service.WalletService`.

```java
public interface WalletService {
    Account openAccount(String ownerId, Currency currency, KycTier kycTier);

    Money getBalance(String accountId);

    TransferResult deposit(String accountId, Money amount, String idempotencyKey, String description);

    TransferResult withdraw(String accountId, Money amount, String idempotencyKey, String description);

    TransferResult transfer(TransferRequest request);

    TransferResult reverse(String originalTransactionId, String idempotencyKey);

    List<LedgerEntry> getStatement(String accountId, Instant from, Instant to);
}
```

**Request/response objects:** `TransferRequest` (source/destination account id,
`Money` amount, idempotency key, free-text description) and `TransferResult`
(the persisted `Transaction` plus a `replayedFromIdempotencyCache` flag so
callers/metrics can distinguish "first application" from "deduped retry").

**Exceptions (all unchecked, all extend `WalletException`):**

| Exception | Thrown when | Typical HTTP mapping |
|---|---|---|
| `AccountNotFoundException` | Account id doesn't exist. | 404 |
| `AccountNotActiveException` | Debit on a FROZEN/CLOSED account, or credit on a CLOSED account. | 409 |
| `InsufficientFundsException` | Debit would make balance negative. | 402/409 |
| `TransferLimitExceededException` | Debit would exceed the KYC tier's daily limit. | 429/403 |
| `InvalidRequestException` | Non-positive amount, same source/destination, blank idempotency key, currency mismatch. | 400 |
| `TransactionNotFoundException` | Reversing an unknown transaction id. | 404 |
| `IllegalTransactionStateException` | Reversing a non-`COMPLETED` or already-`REVERSED` transaction. | 409 |

**Validation responsibilities:** structural validation (amount sign, self-
transfer, blank keys) happens in `WalletServiceImpl`'s public methods, *before*
the idempotency wrapper, so a malformed request never consumes an idempotency
key or touches a lock. Business-rule validation (sufficient funds, account
active, within daily limit, currency match) happens inside the locked critical
section in `Account`, because these checks are only meaningful atomically with
the mutation they guard.

---

## 9. Core Implementation

Full, compiling Java 17+ source lives in `src/main/java/lld/digitalwallet/`.
Key files (all reproduced here only by reference — see the actual files for
the authoritative, compiling version):

- [`model/Money.java`](src/main/java/lld/digitalwallet/model/Money.java) —
  integer-minor-unit monetary value object.
- [`model/Account.java`](src/main/java/lld/digitalwallet/model/Account.java) —
  the lockable, balance-owning aggregate root.
- [`model/Transaction.java`](src/main/java/lld/digitalwallet/model/Transaction.java),
  [`model/LedgerEntry.java`](src/main/java/lld/digitalwallet/model/LedgerEntry.java) —
  immutable transaction/ledger records.
- [`service/WalletServiceImpl.java`](src/main/java/lld/digitalwallet/service/WalletServiceImpl.java) —
  the orchestration core: locking order, idempotency wrapping, double-entry
  writes, reversal state machine.
- [`idempotency/InMemoryIdempotencyStore.java`](src/main/java/lld/digitalwallet/idempotency/InMemoryIdempotencyStore.java) —
  exactly-once-per-key execution via `ConcurrentHashMap#computeIfAbsent`.
- [`demo/WalletDemo.java`](src/main/java/lld/digitalwallet/demo/WalletDemo.java) —
  runnable `main()` demonstrating every flow, including the concurrency stress
  test, end to end.

The single most important method — the deadlock-free atomic transfer — is
reproduced here verbatim because it is the crux of the whole design:

```java
private Transaction postTwoEntryMovement(TransactionType type, String debitAccountId, String creditAccountId,
                                          Money amount, String idempotencyKey, String description,
                                          String reversalOf, boolean enforceDailyLimit) {
    Account debitAccount = accountRepository.getOrThrow(debitAccountId);
    Account creditAccount = accountRepository.getOrThrow(creditAccountId);

    // Deadlock avoidance: always lock in a single global order (ascending accountId), independent
    // of which account happens to be the debit side vs the credit side of *this particular* call.
    Account lockFirst = debitAccount.accountId().compareTo(creditAccount.accountId()) <= 0 ? debitAccount : creditAccount;
    Account lockSecond = lockFirst == debitAccount ? creditAccount : debitAccount;

    lockFirst.lock().lock();
    try {
        lockSecond.lock().lock();
        try {
            requireMatchingCurrency(debitAccount, amount);
            requireMatchingCurrency(creditAccount, amount);

            debitAccount.applyDebit(amount, enforceDailyLimit);
            creditAccount.applyCredit(amount);

            String transactionId = Transaction.newTransactionId();
            Instant now = clock.instant();
            Transaction txn = Transaction.builder()
                    .transactionId(transactionId)
                    .type(type)
                    .sourceAccountId(debitAccountId)
                    .destinationAccountId(creditAccountId)
                    .amount(amount)
                    .status(TransactionStatus.COMPLETED)
                    .createdAt(now)
                    .idempotencyKey(idempotencyKey)
                    .description(description)
                    .reversalOfTransactionId(reversalOf)
                    .build();
            transactionRepository.save(txn);

            ledgerRepository.append(new LedgerEntry(transactionId, debitAccountId, EntryDirection.DEBIT,
                    amount, debitAccount.getBalanceSnapshot().minorUnits(), now));
            ledgerRepository.append(new LedgerEntry(transactionId, creditAccountId, EntryDirection.CREDIT,
                    amount, creditAccount.getBalanceSnapshot().minorUnits(), now));
            return txn;
        } finally {
            lockSecond.lock().unlock();
        }
    } finally {
        lockFirst.lock().unlock();
    }
}
```

---

## 10. Data Structures and Algorithms

| Structure | Used for | Why | Alternative | Complexity |
|---|---|---|---|---|
| `ConcurrentHashMap<String, Account>` | `InMemoryAccountRepository` | O(1) average lookup by account id; thread-safe get/put without external locking for the map itself. | `HashMap` + external lock | O(1) avg get/put, vs O(1) + contention under a single global lock |
| `ConcurrentHashMap<String, Transaction>` (×2: by id, by idempotency key) | `InMemoryTransactionRepository` | Same reasoning; a second map gives O(1) idempotency-key lookups without scanning. | A single map scanned linearly for idempotency key | O(1) vs O(n) |
| `ConcurrentHashMap<String, Transaction>` used via `computeIfAbsent` | `InMemoryIdempotencyStore` | JDK-guaranteed per-key atomicity of `computeIfAbsent` gives "exactly once per key" for free. | A `synchronized` block around a `HashMap` | Both O(1) amortized; `ConcurrentHashMap` gives finer-grained (per-bin) locking instead of one global lock, so unrelated keys don't contend. |
| `CopyOnWriteArrayList<LedgerEntry>` (per account) | `InMemoryLedgerRepository` | Ledger workload is append-rare (one append per transaction leg, while holding the account's own lock already) / read-heavy (statement queries) — the textbook COW use case; readers never block. | A `synchronized` `ArrayList`, or `ConcurrentLinkedQueue` | Append: O(n) copy (acceptable — bounded by how many ledger lines exist for a popular account per unit time, and already serialized by the account lock). Read (statement): O(n) snapshot iteration, lock-free. |
| `ReentrantLock` (one per `Account`) | Mutual exclusion on balance mutation | Reentrancy lets `Account`'s own methods re-lock safely when already held by the orchestrating service call; `tryLock`/`lockInterruptibly` available if needed later (not used here, but the API supports it, unlike `synchronized`). | `synchronized` on the `Account` object | Same O(1) acquire/release; `ReentrantLock` chosen for its richer API (explicit `lock()`/`unlock()` that two different call sites — `Account` and `WalletServiceImpl` — can coordinate on, which `synchronized` doesn't expose). |
| Global consistent lock ordering by `accountId` (string compare) | Deadlock avoidance for two-account transfers | Guarantees all threads acquire multiple locks in the same total order, which is the standard, textbook deadlock-prevention technique (breaks the "circular wait" Coffman condition). | Try-lock with backoff/retry loop | Ordering: O(1) extra comparison, zero retries, deterministic. Try-lock+backoff: no ordering needed but adds retry/backoff complexity and livelock risk under heavy contention — strictly worse for this problem's shape (only ever 1 or 2 locks needed). |
| `Money` as a `long` (minor units) value object | All monetary amounts | Exact integer arithmetic; see section 1 and the `Money` javadoc for the full argument against `double`/`float`. | `BigDecimal` | Both exact. `long` arithmetic is allocation-free and faster; `BigDecimal` carries more precision/scale machinery than a bounded-currency domain needs. Either is a *defensible* staff-level answer — the disqualifying choice is `double`. |

---

## 11. Concurrency and Thread Safety

This section is the heart of the problem. Every claim below is justified, not
asserted, and backed by a test in `tests/lld/digitalwallet/ConcurrentTransferTest.java`.

### 11.1 What shared mutable state exists, and who can race on it

- **`Account.balanceMinorUnits`** — the obvious one. Any two threads debiting or
  crediting the *same* account race on it.
- **`Account.dailyTransferredMinorUnits` / `limitWindowDate`** — a second,
  easy-to-miss piece of mutable state: two concurrent debits must not both read
  "I have room for $40 more today" and both proceed when only $40 total is
  actually available.
- **The idempotency cache** — two threads submitting the *same* idempotency key
  concurrently (classic "client fired the request twice because the first
  response was slow") must not both execute the transfer.
- **Two different accounts in a single transfer** — the subtle one: a transfer
  needs an invariant ("debit happened if and only if credit happened, and no
  other thread saw a half-applied state") that spans *two* independently
  lockable objects.

### 11.2 Why a lock, not a lock-free CAS, for the balance

A debit is **not** a single atomic field update — it's "read balance, check
`balance >= amount`, check `dailyTransferred + amount <= limit`, *then* write
`balance - amount` and `dailyTransferred + amount`." A single
`AtomicLong.compareAndSet` only protects one of those two writes atomically
with its own read; it cannot make a *multi-field, multi-step* check-then-act
sequence atomic. You could build a CAS loop over an immutable "account state"
record (read whole state, compute new state, CAS the whole thing), but the
moment you need to coordinate that mutation with a *second* account's mutation
(a transfer) and a ledger write, you need a critical section anyway — so
`ReentrantLock` per account, used consistently, is both simpler and strictly
more capable here. (See section 16 for a full optimistic-locking write-up as
the alternative and when it wins.)

### 11.3 Lock granularity

One `ReentrantLock` per `Account` — not one global lock for the whole wallet
system. A global lock would serialize *every* transfer in the system behind a
single mutex, which is both unnecessary (transfers between disjoint account
pairs have no data dependency) and a textbook scalability bottleneck. Per-
account locking lets `N` unrelated transfers proceed fully in parallel; only
transfers that actually share an account (as source or destination) ever
contend.

### 11.4 Deadlock avoidance: consistent global lock ordering

The classic deadlock shape for "lock two resources": Thread 1 does `A→B`
(locks A, then wants B); Thread 2 does `B→A` (locks B, then wants A). If each
thread naively locks "source, then destination," Thread 1 holds A waiting for
B while Thread 2 holds B waiting for A — a true deadlock (circular wait).

**Fix implemented:** `WalletServiceImpl.postTwoEntryMovement` never locks
"source then destination." It locks "whichever of the two accounts has the
lexicographically smaller `accountId`, then the other one" — **regardless of
which one is the debit side or the credit side for this particular call**.
Since every thread, no matter its transfer direction, computes the same total
order over the same two accounts, it's structurally impossible for two threads
to hold-one-wait-on-the-other in opposite order. This is the standard
"lock ordering" deadlock-prevention technique, applied to the specific
two-account shape of this problem.

This is directly tested: `ConcurrentTransferTest.twoThreadsTransferringInOppositeDirectionsDoNotDeadlock`
runs two threads doing `A→B` and `B→A` 300 times each, concurrently, under an
8-second `@Timeout` — if lock ordering were wrong, this test would hang (not
fail an assertion) until the timeout fires and JUnit reports it as a failure.

### 11.5 Atomicity of the whole transfer (not just the two locks)

Holding both locks isn't enough on its own — you also need the **debit, the
credit, the transaction write, and the two ledger writes** to all happen while
both locks are held, and the locks to be released only after all of it is
durably recorded (in this in-memory model: written to the repositories). This
is exactly what `postTwoEntryMovement`'s `try { ... } finally { unlock }`
structure guarantees: any exception thrown mid-way (e.g.
`InsufficientFundsException` from the debit check) unwinds through the
`finally` blocks, releasing locks, with **no** partial mutation having
occurred (credit only ever runs *after* debit succeeds) and **nothing**
persisted (the transaction/ledger writes are the last statements in the
block).

### 11.6 Idempotency as a concurrency problem, not just a correctness problem

Two threads submitting the identical idempotency key concurrently (e.g. a
client's "submit" button double-tapped, both requests in flight at once) must
not both execute `postTwoEntryMovement`. `InMemoryIdempotencyStore` delegates
to `ConcurrentHashMap#computeIfAbsent`, whose **JDK contract** is that the
mapping function is invoked **at most once per key**, atomically with respect
to other threads using that key — the losing thread blocks until the winner's
computation finishes, then both observe the identical cached `Transaction`.
This is why `transfer`/`deposit`/`withdraw`/`reverse` all wrap their real work
in this call: the exactly-once guarantee is enforced *before* any account lock
is even considered.

**Caveat called out explicitly (a known follow-up question):** if the wrapped
computation throws (e.g. insufficient funds *at that moment*), `computeIfAbsent`
does **not** cache anything — a subsequent retry with the same key is allowed
to actually re-attempt the operation. This is intentional: we only want to
deduplicate *successful* side effects, not block a legitimate "retry after a
transient failure."

### 11.7 Visibility

`Account.balanceMinorUnits`, `status`, and `kycTier` are declared `volatile` in
addition to being guarded by the lock. This is **not** redundant: it lets a
non-authoritative, lock-free read (`getBalanceSnapshot()`, used for e.g. a
dashboard poller) observe the latest value written by any thread without
itself acquiring the lock, while every *mutation* still goes through the lock
for atomicity. This is the standard "volatile for visibility, lock for
atomicity of compound operations" pattern — `volatile` alone would not be
enough to prevent overdraft (it doesn't help with the check-then-act race),
but it is exactly enough to make a plain read never see a stale/torn value.

### 11.8 What is explicitly *not* lock-free here, and why that's fine

No part of this design claims to be lock-free/wait-free. For a wallet, the
contended section (a handful of arithmetic operations plus two map writes) is
short, and correctness/simplicity dominate over the marginal throughput a
lock-free structure would buy — see section 14 for where this would actually
become a bottleneck (a single "hot" account, e.g. a popular merchant) and what
to do about it.

---

## 12. Persistence and Database Design

**In-memory for this exercise, with the interfaces designed so persistence is
a drop-in swap.** Why in-memory is the right call for the interview exercise:
the problem is about the *locking/idempotency/ledger design*, not about SQL;
introducing a real database (or even an embedded one) would add setup
complexity without adding design signal, and the repository interfaces already
force the right seams.

**If this were backed by a relational database**, the schema would look like:

```sql
CREATE TABLE account (
    account_id        VARCHAR(64) PRIMARY KEY,
    owner_id          VARCHAR(64) NOT NULL,
    currency          CHAR(3)     NOT NULL,
    balance_minor     BIGINT      NOT NULL CHECK (balance_minor >= 0),
    status            VARCHAR(16) NOT NULL,
    kyc_tier          VARCHAR(32) NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0,  -- optimistic-locking column
    daily_transferred_minor BIGINT NOT NULL DEFAULT 0,
    limit_window_date DATE,
    INDEX idx_owner (owner_id)
);

CREATE TABLE transaction (
    transaction_id    VARCHAR(64) PRIMARY KEY,
    type              VARCHAR(16) NOT NULL,
    source_account_id VARCHAR(64) NULL REFERENCES account(account_id),
    destination_account_id VARCHAR(64) NULL REFERENCES account(account_id),
    amount_minor      BIGINT      NOT NULL,
    currency          CHAR(3)     NOT NULL,
    status            VARCHAR(16) NOT NULL,
    created_at        TIMESTAMP   NOT NULL,
    idempotency_key   VARCHAR(128) NOT NULL,
    description       VARCHAR(256),
    reversal_of_transaction_id VARCHAR(64) NULL REFERENCES transaction(transaction_id),
    UNIQUE KEY uq_idempotency_key (idempotency_key)  -- THIS is the production idempotency guarantee
);

CREATE TABLE ledger_entry (
    entry_id          VARCHAR(64) PRIMARY KEY,
    transaction_id    VARCHAR(64) NOT NULL REFERENCES transaction(transaction_id),
    account_id        VARCHAR(64) NOT NULL REFERENCES account(account_id),
    direction         VARCHAR(8)  NOT NULL,   -- DEBIT / CREDIT
    amount_minor      BIGINT      NOT NULL,
    balance_after_minor BIGINT    NOT NULL,
    created_at        TIMESTAMP   NOT NULL,
    INDEX idx_account_created (account_id, created_at)  -- powers getStatement(accountId, from, to)
);
```

**Transactions & isolation:** a transfer would run inside a single DB
transaction:
```sql
BEGIN;
SELECT balance_minor, status, version FROM account WHERE account_id = :smaller_id FOR UPDATE;
SELECT balance_minor, status, version FROM account WHERE account_id = :larger_id  FOR UPDATE;
-- (always query in ascending account_id order — same deadlock-avoidance
--  argument as section 11, now applied to row locks instead of in-process locks)
UPDATE account SET balance_minor = balance_minor - :amount, version = version + 1
  WHERE account_id = :debit_id AND balance_minor >= :amount;  -- 0 rows updated => insufficient funds, ROLLBACK
UPDATE account SET balance_minor = balance_minor + :amount, version = version + 1 WHERE account_id = :credit_id;
INSERT INTO transaction (...) VALUES (...);  -- UNIQUE(idempotency_key) makes a racing duplicate INSERT fail fast
INSERT INTO ledger_entry (...) VALUES (...), (...);
COMMIT;
```
`READ COMMITTED` isolation is sufficient given the explicit `SELECT ... FOR
UPDATE` row locks (acquired in a fixed order) — we don't need
`SERIALIZABLE`'s overhead because the invariant we care about (no overdraft)
is enforced by the `WHERE balance_minor >= :amount` guard directly, not by
isolation level.

**Why in-memory is fine for the in-process demo:** the repository interfaces
(`AccountRepository`, `TransactionRepository`, `LedgerRepository`) are the only
places that would need a JDBC implementation; `WalletServiceImpl`'s logic
(validation, lock ordering, idempotency wrapping) is identical either way —
proving the abstraction boundary is in the right place, which is exactly what
this section of an interview is checking for.

---

## 13. Failure Scenarios

| Scenario | What happens in this design | Recovery |
|---|---|---|
| **Partial failure mid-transfer** (e.g. process crash after debiting source but before crediting destination) | In-memory: impossible mid-critical-section (single JVM, no I/O between debit and credit). In a DB-backed version: both updates are in one SQL transaction, so a crash before `COMMIT` means *neither* update is durable (atomicity from the DB transaction, not from application code). | DB: automatic rollback on reconnect. In-memory demo: N/A (no crash recovery for a non-durable store — named as a limitation). |
| **Duplicate client request (retry after timeout)** | Deduped by `IdempotencyStore` — see section 11.6. | No action needed; same result returned. |
| **Concurrent debit races past a check** (the "TOCTOU" classic) | Impossible by construction: the balance check and the mutation are inside the same lock acquisition, so no other thread can observe or act on the account between the check and the write. | N/A. |
| **Invalid state transition** (reversing an already-reversed transaction, reversing a non-existent transaction) | Rejected with `IllegalTransactionStateException` / `TransactionNotFoundException` before any mutation. | Caller surfaces the error; no cleanup needed since nothing was mutated. |
| **Resource exhaustion** (idempotency map / ledger list growing unbounded) | Named as an explicit limitation of the in-memory demo (no TTL/eviction). | Production: TTL-expire idempotency keys (e.g. 24h, matching typical client retry windows); archive old ledger entries to cold storage. |
| **Timeout on the client side, but the server actually succeeded** | This is precisely what idempotency keys solve — the client retries with the same key and gets the already-completed result instead of double-spending. | No special handling needed beyond the idempotency design already in place. |
| **Insufficient funds discovered mid-transfer** | `applyDebit` throws before any state is mutated; `applyCredit` on the destination never runs (debit always executes first and the exception propagates through to the caller, releasing both locks via `finally`). | Caller receives `InsufficientFundsException`; system state is provably unchanged (tested). |
| **Corrupted/inconsistent read of balance** (torn read) | Impossible: `balanceMinorUnits` is a `long` (always atomically readable/writable per the JLS) and additionally `volatile`, so no thread ever observes a torn or stale-beyond-visibility-guarantees value. | N/A. |
| **Two reversal requests racing for the same original transaction, different idempotency keys** | Both pass the idempotency check (different keys) but only one wins the account-lock race; the other, running second, finds `original.status() == REVERSED` already and throws `IllegalTransactionStateException` — demonstrated by `reversingTheSameTransactionTwiceIsRejectedOnTheSecondAttempt`. | Caller of the losing request sees a clear rejection, not silent double-reversal. |
| **Clean-up after a failed reversal attempt** | Nothing to clean up — the failed branch never wrote a ledger entry or mutated a balance; the only write (if any) attempted was the final `transactionRepository.save(original.withStatus(REVERSED))`, which only runs *after* the reversal's own two-account movement already succeeded. | N/A by construction. |

---

## 14. Performance and Scalability

**Single-process bottlenecks and fixes:**
- **A single "hot" account** (e.g. a popular merchant receiving thousands of
  concurrent incoming transfers) serializes on that one account's lock no
  matter how many CPUs are available. Fix within the same process: nothing
  structural to do about the *correctness* requirement (you cannot parallelize
  mutations to one balance), but you can reduce *lock hold time* (already
  minimal here — a handful of field writes) and move read-heavy work (e.g.
  statement generation) off the hot path entirely, since `getStatement` never
  touches the account lock.
- **Idempotency map growth** — unbounded in the demo; fix is TTL eviction
  (discussed in section 13).
- **`CopyOnWriteArrayList` per account for the ledger** — becomes expensive if a
  single account accumulates millions of ledger lines (O(n) copy per append).
  Fix: switch to a structure that appends in O(1) (e.g. a persistent/immutable
  list, or simply paginate by flushing older entries to a database table and
  keeping only a recent window in memory).

**Multi-threaded (same process, more cores):** the design already parallelizes
well *across* unrelated account pairs — N independent transfers run fully
concurrently, limited only by actual data dependencies (shared accounts), which
is the best a correctness-preserving design can do for this problem shape.

**Distributed extension (discussed, not built — this is an LLD problem, not a
systems-design one):** Once accounts are sharded across multiple DB nodes (e.g.
by `accountId` hash), a transfer between two accounts on *different* shards
needs either (a) a distributed transaction (2PC — slow, operationally painful)
or (b) a **saga**: a sequence of local transactions with compensating actions,
which is exactly what the `REVERSAL` transaction type in this design already
models — a cross-shard transfer becomes "debit locally with a `PENDING` state,
publish an event, credit remotely, confirm; on failure, reverse the debit using
the exact same reversal mechanism already built here." This is why the domain
model already has `TransactionStatus` as an enum rather than a boolean
"succeeded" flag — it was designed with room for a real `PENDING` state without
an API change. **Explicitly not built** because distributing an LLD problem
over-scopes a 45-60 minute interview; mentioning this reasoning is itself the
signal a staff-level interviewer is looking for.

---

## 15. Testing Strategy

Representative tests live in `tests/lld/digitalwallet/`:

- **`MoneyTest`** — unit tests for the value object: exact minor-unit
  conversion, no floating-point drift across repeated addition, currency-
  mismatch rejection, value-based equality.
- **`WalletServiceImplTest`** — integration-style tests against the real
  in-memory stack (no mocks — the repositories and idempotency store are the
  actual in-memory implementations, since they're fast and deterministic):
  - Happy path: transfer moves money correctly (`transferMovesMoneyBetweenAccountsAtomically`).
  - Double-entry invariant: debit leg + credit leg net to zero
    (`transferWritesTwoBalancedLedgerEntries`).
  - Idempotency: duplicate key is not double-applied (`duplicateIdempotencyKeyIsNotAppliedTwice`).
  - Negative/boundary: insufficient funds leaves balances untouched
    (`transferBeyondBalanceIsRejectedAndLeavesBalancesUnchanged`), daily limit
    enforcement (`transferExceedingDailyLimitIsRejected`), self-transfer
    rejection (`selfTransferIsRejected`).
  - Edge case: frozen account blocks debits but still accepts credits
    (`debitsAreBlockedOnFrozenAccountButCreditsStillLand`).
  - Reversal: full round-trip reversal restores balances without mutating the
    original transaction in place (`reversalUndoesACompletedTransferWithoutMutatingTheOriginal`),
    double-reversal is rejected (`reversingTheSameTransactionTwiceIsRejectedOnTheSecondAttempt`),
    and a reversal retried with the *same* idempotency key is safely deduped
    rather than double-refunding (`reversingWithTheSameIdempotencyKeyTwiceIsSafelyDeduped`).
  - Deposit/withdraw round trip (`depositAndWithdrawalRoundTripRestoresBalance`).
- **`ConcurrentTransferTest`** — concurrency/failure-injection tests:
  - 16 threads, 200 transfers each, bidirectional between two accounts, under a
    20s `@Timeout`: asserts no account ever goes negative and the total balance
    across both accounts is conserved
    (`concurrentBidirectionalTransfersNeverOverdraftNeverDeadlockAndConserveTotal`).
  - A narrower, deterministic two-thread opposite-direction test under a tight
    8s timeout, specifically isolating the deadlock-freedom property
    (`twoThreadsTransferringInOppositeDirectionsDoNotDeadlock`) — if lock
    ordering regressed to "always lock source then destination," this test
    would hang until the `@Timeout` fails it.

**Verification performed for this exercise:** compiled with `javac` against
JUnit 5 API jars and executed with the JUnit Platform Console Standalone
launcher; all 19 tests pass, including 3 repeated runs of the concurrency
suite to rule out flakiness. See the top-level summary for exact commands and
output.

---

## 16. Trade-offs and Alternatives

| Decision | Chosen approach | Alternative | Pros/cons | When the alternative wins |
|---|---|---|---|---|
| **Monetary representation** | `long` minor units | `BigDecimal` | `long`: faster, allocation-free, exact. `BigDecimal`: also exact, more familiar API, supports fractional-minor-unit math (e.g. some crypto/points systems). | When the domain genuinely needs sub-minor-unit precision (e.g. interest accrual, loyalty points with fractional rates) — `BigDecimal` or a fixed-point scaled-integer scheme becomes necessary. |
| **Concurrency control for balance mutation** | Pessimistic locking (`ReentrantLock` per account / `SELECT FOR UPDATE` in a DB) | Optimistic locking (version column + compare-and-swap-style retry) | Pessimistic: no wasted work on conflict, simple reasoning, but holds a lock for the full critical section (contention cost if the section were long). Optimistic: no lock held during the "think" phase, better under *low*-contention/high-read workloads, but requires a retry loop and can **livelock** under high contention on a hot account (exactly the workload a popular wallet account has). | Optimistic locking wins when conflicts are rare and the critical section is read-heavy with occasional writes (e.g. updating an account's *metadata*, not its balance under contention). For the specific "many debits racing on the same balance" shape, pessimistic is the better default — this is a defensible, discussable choice either way, and is exactly the kind of thing a staff interviewer will probe (see STAFF_LEVEL_DEEP_DIVE.md Q on this). |
| **Idempotency storage** | In-process `ConcurrentHashMap` | DB unique constraint on `idempotency_key`, or Redis `SET key val NX EX ttl` | In-memory: zero extra infra, but lost on restart and doesn't work across multiple service instances. DB/Redis: durable, works across instances, but adds a network round-trip and an extra failure mode (what if the idempotency store is briefly unavailable?). | Any real multi-instance deployment needs the DB/Redis version — named explicitly as the production evolution in section 17. |
| **Reversal model** | New compensating `Transaction` (original never mutated except its `status`) | Mutate the original transaction's amount/state in place | New compensating transaction: fully auditable (you can see *both* the mistake and the fix in the ledger), matches real accounting practice. In-place mutation: simpler, but destroys the audit trail and makes "what actually happened, in order" unrecoverable. | In-place mutation essentially never wins for a financial ledger — it's included here only to show it was considered and rejected, not as a live option. |
| **Full-amount-only reversal** | Reversal always undoes the entire original amount | Partial reversal (refund part of a transfer) | Full-only: much simpler invariant ("a transaction is reversed, or it isn't"). Partial: needed for real-world refund scenarios (e.g. a partial merchant refund) but requires tracking "amount remaining reversible" per transaction and a different invariant (sum of reversals ≤ original). | A production wallet almost certainly needs partial reversal eventually — named as a natural, well-scoped extension (section 17) rather than built now, to keep the core interview-sized design legible. |
| **Lock-ordering by `accountId` string** | Deterministic total order via `String.compareTo` | A separately-maintained numeric "lock rank" per account, or try-lock-with-backoff | String ordering: zero extra state, trivially correct as long as account ids are unique (they are, by construction — UUID-based). Try-lock+backoff: avoids needing *any* global order but introduces livelock risk and retry complexity for no benefit here (only ever 2 locks needed, never N). | Try-lock+backoff is the right tool when you might need to lock an *unbounded, order-agnostic* set of resources (e.g. a batch job locking many accounts at once) — not needed for pairwise transfers. |

---

## 17. Production Evolution

*(Kept explicitly separate from the core interview solution above — this is
"what I'd do with 3 more months and a team," not "what I'd design in the
interview room.")*

- **Durable, multi-instance idempotency store:** Redis (`SET NX EX`) or a DB
  table with a unique constraint on `idempotency_key`, with a TTL-based cleanup
  job; `IdempotencyStore` is already the seam for this.
- **Real persistence:** JDBC repositories implementing the three repository
  interfaces unchanged, backed by the schema in section 12, with proper
  connection pooling and retry-on-deadlock (DB deadlock detection is probabilistic;
  even with correct lock ordering, a DB can still occasionally report a
  deadlock under its own internal lock-manager heuristics, and the application
  should retry the whole transaction once in that case).
- **Monitoring/metrics:** per-account-tier transfer latency and error-rate
  dashboards; a counter for `replayedFromIdempotencyCache` to track client
  retry rates (a spike often indicates an upstream timeout/network issue, not
  a wallet bug); balance-reconciliation job that independently sums
  `ledger_entry` rows per account and alerts on any drift from the `account`
  table's `balance_minor` (the double-entry invariant as a *production
  safety net*, not just a design principle).
- **Logging:** structured, correlation-id-tagged logs for every mutating call,
  with the idempotency key always logged (never the raw account/PII without
  redaction policy).
- **Distributed coordination (if/when sharded):** saga-based cross-shard
  transfers as sketched in section 14, with a dedicated orchestrator and a
  durable outbox pattern for the "publish an event after a local commit"
  problem (the classic dual-write problem — commit the local DB transaction
  and have a background process read the outbox table and publish, rather
  than publishing in the same step as the commit).
- **Fault tolerance:** circuit breakers around any external dependency (bank
  rail for deposit/withdraw), dead-letter handling for failed async reversal
  attempts, and a clear SLA on "how long can a deposit be `PENDING` before
  it's treated as failed and auto-reversed."
- **Operational concerns:** account freeze/unfreeze needs an audit trail of
  *who* froze an account and *why* (compliance requirement in any real
  deployment); rate limiting at the API gateway layer in addition to the
  domain-level daily KYC limit (defense in depth).
- **API evolution/backward compatibility:** `TransferRequest`/`TransferResult`
  are already separate from the domain model (`Transaction`) specifically so
  the wire format can gain fields (e.g. a `feeAmount`) without touching
  `Transaction`; new `TransactionType`/`TransactionStatus` enum values should
  be added additively, with all consumers required to have a `default` case
  (as `WalletServiceImpl.doReverse`'s `switch` already does) so new types
  degrade safely instead of crashing unknown-type handling in old deployed
  code.
