# Implementation Guide — Digital Wallet

A practical, sequential guide to actually writing this code live, with timing
and narration guidance. Complements INTERVIEW_PLAYBOOK.md (how to run the
*conversation*) — this is about how to run the *typing*.

---

## Which classes to write first

1. **`Money`** — zero dependencies, everything else needs it, and it's the
   fastest way to demonstrate the `double`-vs-integer-minor-units decision
   concretely instead of just talking about it.
2. **`AccountStatus`, `KycTier` enums** — trivial, 30 seconds each, needed by `Account`.
3. **`Account`** — the central lockable entity; this is where you spend real
   narration time (the lock, the invariant check, why it's one critical section).
4. **Exceptions** (`InsufficientFundsException`, `AccountNotFoundException`,
   `InvalidRequestException` at minimum) — small, needed so `Account`/`WalletServiceImpl`
   compile; write them as you need them rather than all upfront.
5. **`TransactionType`, `TransactionStatus`, `EntryDirection` enums, then
   `Transaction`, `LedgerEntry`** — straightforward immutable value objects.
6. **Repository interfaces + in-memory implementations** — write the interface
   and its one in-memory implementation back-to-back; don't batch all three
   interfaces before any implementation, it reads as over-planning live.
7. **`IdempotencyStore` + `InMemoryIdempotencyStore`** — small, but narrate the
   `computeIfAbsent` atomicity guarantee as you write it; this is a
   "look, I know exactly why this one line is correct" moment.
8. **`WalletService` interface** — write this *before* `WalletServiceImpl`,
   even though it's tempting to just start the implementation — committing to
   the public contract first keeps the implementation honest.
9. **`WalletServiceImpl`** — `openAccount`/`getBalance` first (trivial),
   then `deposit`/`withdraw` (single-account, no lock-ordering), then
   `transfer` (the centerpiece), then `reverse` if time allows.
10. **A `Main`/demo class** — always end with this if time allows; it's cheap
    proof the whole thing actually works, and it's a natural place to
    demonstrate the concurrency behavior live.

---

## Minimal compilable implementation outline

If you only have time to type a skeleton, this is the smallest version that
still demonstrates the core ideas (expand from here):

```java
public final class Money {
    private final long minorUnits;
    private final Currency currency;
    // ofMinorUnits/ofMajorUnits factories, add/subtract, equals/hashCode
}

public final class Account {
    private final String accountId;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile long balanceMinorUnits;
    private volatile AccountStatus status;

    public ReentrantLock lock() { return lock; }

    public void applyDebit(Money amount) {
        lock.lock();
        try {
            if (balanceMinorUnits < amount.minorUnits()) throw new InsufficientFundsException(...);
            balanceMinorUnits -= amount.minorUnits();
        } finally { lock.unlock(); }
    }

    public void applyCredit(Money amount) {
        lock.lock();
        try { balanceMinorUnits += amount.minorUnits(); }
        finally { lock.unlock(); }
    }
}

public interface WalletService {
    TransferResult transfer(TransferRequest request);
    // ... other methods
}

public final class WalletServiceImpl implements WalletService {
    public TransferResult transfer(TransferRequest req) {
        return new TransferResult(idempotencyStore.computeIfAbsent(req.idempotencyKey(), () -> {
            Account a = accounts.getOrThrow(req.sourceAccountId());
            Account b = accounts.getOrThrow(req.destinationAccountId());
            Account first = a.accountId().compareTo(b.accountId()) <= 0 ? a : b;
            Account second = first == a ? b : a;
            first.lock().lock();
            try {
                second.lock().lock();
                try {
                    a.applyDebit(req.amount());
                    b.applyCredit(req.amount());
                    return buildAndSaveTransaction(...);
                } finally { second.lock().unlock(); }
            } finally { first.lock().unlock(); }
        }), false);
    }
}
```

This skeleton alone — without ledger entries, reversal, or daily limits —
already demonstrates the two things that matter most: no-overdraft and
deadlock-free two-account locking. Everything else in the full implementation
is important but secondary to these two.

---

## Suggested implementation sequence (with rough timing for a 45-60 min round)

| Step | What | Time |
|---|---|---|
| 1 | `Money` | 4 min |
| 2 | Enums (`AccountStatus`, `KycTier`, `TransactionType`, `TransactionStatus`, `EntryDirection`) | 2 min |
| 3 | `Account` with lock + `applyDebit`/`applyCredit` | 8 min |
| 4 | Core exceptions | 3 min |
| 5 | `Transaction`, `LedgerEntry` | 5 min |
| 6 | Repository interfaces + in-memory impls | 6 min |
| 7 | `IdempotencyStore` + in-memory impl | 4 min |
| 8 | `WalletService` interface | 2 min |
| 9 | `WalletServiceImpl`: `openAccount`, `getBalance`, `deposit`, `withdraw` | 6 min |
| 10 | `WalletServiceImpl.transfer` (the centerpiece — narrate lock ordering live) | 8 min |
| 11 | `reverse` (if time remains) | 6 min |
| 12 | Demo/test (if time remains) | 6 min |

---

## Important methods (the ones worth getting exactly right)

- **`Account.applyDebit(Money, boolean enforceDailyLimit)`** — the single most
  important method in the system. Get the order right: check status active →
  check sufficient funds → check daily limit → mutate balance → increment
  daily-transferred counter → increment version. All under one lock
  acquisition.
- **`WalletServiceImpl.postTwoEntryMovement`** — the lock-ordering logic
  specifically: `lockFirst`/`lockSecond` must be computed from the two
  account ids directly (not from "source"/"destination" roles), or the whole
  deadlock-avoidance argument breaks.
- **`InMemoryIdempotencyStore.computeIfAbsent`** — a one-line method, but the
  one line that provides the entire idempotency guarantee; get this exactly
  right (`cache.computeIfAbsent(key, k -> computation.get())`), don't
  "improve" it with a manual `containsKey`/`get`/`put` sequence, which would
  reintroduce the exact race it's meant to prevent.

---

## Common coding mistakes (and how to avoid them live)

1. **Locking "source then destination" instead of a consistent global
   order.** This is the single most likely mistake under interview pressure —
   it looks correct, compiles, passes a quick manual test, and only fails
   under genuine concurrent opposite-direction load. Say the ordering rule out
   loud *before* writing the lock-acquisition code, so you don't fall back to
   the "obvious" (wrong) version while typing under pressure.
2. **Checking "has enough balance" and mutating the balance as two separate,
   un-locked steps.** Always keep the check and the mutation inside the same
   lock acquisition — never split them across two method calls with a gap in
   between where another thread could interleave.
3. **Using `double`/`float` for amounts "just for the demo, I'll fix it
   later."** Don't — this is one of the most heavily-weighted red flags in
   this specific problem; even a prototype should use `long` minor units or
   `BigDecimal` from the first line.
4. **Caching idempotency results even when the computation throws.** If you
   hand-roll the idempotency check instead of using `computeIfAbsent`, it's
   easy to accidentally cache a failed attempt too, which then wrongly blocks
   all future retries of a transient failure.
5. **Mutating a `Transaction`'s status in place** instead of creating a new
   object. This breaks the "immutable, safe to hand to any thread" property
   that the rest of the design leans on.
6. **Forgetting `finally` for `unlock()`.** An exception thrown between
   `lock()` and `unlock()` without a `finally` leaves the lock held forever —
   a startlingly common live-coding mistake under time pressure. Write the
   `try { ... } finally { unlock(); }` skeleton *before* filling in the body.
7. **Locking both accounts without checking they're different first.** If
   `sourceAccountId.equals(destinationAccountId)`, "lock A then lock A again"
   on a non-reentrant-from-two-different-logical-call-sites basis can behave
   unexpectedly (with `ReentrantLock` it technically works since it's the same
   thread, but the resulting self-transfer is semantically nonsensical and
   should be rejected by validation before ever reaching the locking code).

---

## Edge cases (reiterated from README section 5/13, as a coding checklist)

- [ ] Negative or zero transfer amount → reject before touching any lock.
- [ ] Source == destination → reject before touching any lock.
- [ ] Unknown account id → `AccountNotFoundException` from `getOrThrow`.
- [ ] Insufficient funds → reject inside the lock, no partial mutation.
- [ ] Frozen account debited → reject; frozen account credited → allowed.
- [ ] Closed account credited or debited → reject either way.
- [ ] Currency mismatch between account and amount → reject before mutation.
- [ ] Daily limit exceeded → reject inside the lock, same atomic check as
      the balance check.
- [ ] Duplicate idempotency key → return cached result, no re-execution.
- [ ] Reversing a non-existent transaction → `TransactionNotFoundException`.
- [ ] Reversing an already-reversed transaction → `IllegalTransactionStateException`.
- [ ] Reversal with a duplicate idempotency key → deduped, not double-applied.

---

## How to narrate the code while writing it

Narrate the *why*, not the syntax — the interviewer can read Java; they can't
read your reasoning unless you say it. Good narration sounds like:

> "I'm putting the balance check and the mutation inside the same lock
> acquisition on purpose — if I split them into two calls, two threads could
> both pass the check before either one's write happens, and I'd overdraft
> the account. That's the bug I'm specifically designing against here."

> "I'm computing which account to lock first based on account id, not based
> on which one is the source — because if I locked 'source first' always,
> two transfers going in opposite directions between the same two accounts
> could deadlock. This way, both threads agree on lock order no matter which
> direction they're transferring."

Avoid narrating syntax ("now I'm declaring a private final field") — that
wastes time and signals you're thinking about typing, not design.

---

## What can be safely omitted under time pressure

(Mirrors README section 16/17, reframed as "what to actually skip while typing.")

- Daily KYC limits — mention the enum exists, skip the full reset-window logic.
- Reversal — mention the shape of the solution ("it's a two-account movement
  in the opposite direction, same locking path"), skip typing it if out of time.
- Account statement query — keep the ledger writes, skip the query method.
- Freeze/unfreeze — mention the `AccountStatus` enum, skip implementing the
  methods if truly pressed.
- Javadoc/comments — write them if there's slack time at the end; don't let
  comment-writing eat into core-logic time live.

**Never skip:** the lock, the balance check inside the lock, and the
lock-ordering rule for two-account operations — these are the parts the
interview is actually testing.

---

## A simplified version implementable in 20-30 minutes

For a very short round or a warm-up exercise, this is the smallest design that
still demonstrates the core competency (no-overdraft + deadlock-free
two-account transfer), deliberately dropping ledger entries, reversal,
idempotency, and KYC limits entirely:

```java
final class Account {
    final String id;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile long balanceCents;

    Account(String id, long initialBalanceCents) {
        this.id = id;
        this.balanceCents = initialBalanceCents;
    }

    ReentrantLock lock() { return lock; }

    void debit(long cents) {
        lock.lock();
        try {
            if (balanceCents < cents) throw new IllegalStateException("insufficient funds");
            balanceCents -= cents;
        } finally { lock.unlock(); }
    }

    void credit(long cents) {
        lock.lock();
        try { balanceCents += cents; }
        finally { lock.unlock(); }
    }

    long balance() { return balanceCents; }
}

final class SimpleWallet {
    void transfer(Account from, Account to, long cents) {
        if (from.id.equals(to.id)) throw new IllegalArgumentException("self-transfer");
        Account first = from.id.compareTo(to.id) <= 0 ? from : to;
        Account second = (first == from) ? to : from;
        first.lock().lock();
        try {
            second.lock().lock();
            try {
                from.debit(cents);
                to.credit(cents);
            } finally { second.lock().unlock(); }
        } finally { first.lock().unlock(); }
    }
}
```

This compiles standalone, demonstrates the no-overdraft invariant and the
deadlock-free lock ordering in under 40 lines, and is a reasonable answer if
asked to "just show me the core transfer logic" with no further scope.
