# Interview Playbook — Digital Wallet

A speakable, timed walkthrough for driving this problem live in a 30/45/60-minute
round. Pairs with README.md (the reference design) and
STAFF_LEVEL_DEEP_DIVE.md (the hard follow-ups).

---

## 1. How to open the discussion

Don't start coding. Start by restating the problem in your own words and
proposing scope, out loud, in under 90 seconds:

> "So we're designing the core of a digital wallet — think Venmo or Paytm.
> Users have a balance, they can top up from a bank, send money to each other,
> and withdraw. I want to treat this as a single-process design problem first —
> get the domain model and concurrency story right — and only talk about
> distribution/scale as an extension at the end, unless you want me to go there
> sooner. Does that match what you had in mind, or is there a specific angle
> you want me to focus on — concurrency, the API design, or the data model?"

This does three things: signals you know the shape of the problem, sets scope
explicitly (so you're not penalized later for "not distributing it"), and
hands control back to the interviewer to steer.

---

## 2. First 5 clarifying questions to ask

1. "Is this purely in-memory for the exercise, or should I assume a real
   database from the start?" — changes whether you reach for `ReentrantLock`
   or talk in terms of `SELECT ... FOR UPDATE`.
2. "Single currency per account, or do we need FX/multi-currency transfers?"
   — multi-currency is a materially bigger domain model; worth ruling out early.
3. "Is 'no overdraft' a hard rule, or can an account have a credit line?"
   — changes what the core invariant even is.
4. "Do we need idempotent retries on the transfer API, or can I assume the
   client never double-submits?" — if they say "don't worry about it," you've
   just been told to cut your biggest scoring opportunity; push back gently:
   "I'd actually like to keep it, since duplicate-charge-on-retry is the kind
   of bug that actually ships to production — I'll keep it lightweight."
5. "Should a reversal/refund flow be in scope, or just the forward transfer?"
   — tells you whether to budget time for the reversal state machine.

---

## 3. Requirements to establish

Say these out loud and get a nod before moving on — it anchors the rest of the
interview to an agreed contract:

- Functional: open account, deposit, withdraw, transfer, reverse, get
  statement, idempotent mutating calls.
- Non-functional: no overdraft ever (even concurrently), no double-spend on
  retried requests, auditable history, reasonable single-process performance.

---

## 4. How to identify core entities

Walk it top-down from the nouns in the problem statement, then immediately
separate **entities** (have identity + lifecycle) from **value objects**
(defined purely by their value):

> "`Account` is clearly an entity — it has an id and a lifecycle. `Money` is a
> value object — two `Money(500, USD)`s are interchangeable, I don't need
> identity for them, which is exactly why I'll make it immutable. A
> `Transaction` is interesting: it looks like it should be mutable ('pending'
> then 'completed') but I'm going to make it immutable too, and model state
> changes as replacing it with a new object — that's what makes the ledger
> safe to read concurrently without locking the reader."

Then name the rest quickly: `LedgerEntry` (the actual debit/credit posting —
distinct from `Transaction`, which is the client-facing record), `KycTier`,
`AccountStatus`.

---

## 5. How to explain class relationships

Lead with ownership, not UML syntax:

> "An `Account` owns its status and KYC tier — composition, no independent
> lifecycle. It *references* `Money` for its balance, but doesn't own
> `Money` — it's just a shared, immutable value. A `Transaction` is
> associated with, not composed of, its `LedgerEntry` rows — a transfer
> produces exactly two ledger entries, but they live in their own append-only
> store so I can query 'all entries for this account' without going through
> any particular transaction object."

This is also where you preempt the double-entry question before they ask it:

> "Every transfer writes exactly two ledger entries — a debit on the source,
> a credit on the destination, same amount, same transaction id — so the
> ledger always nets to zero per transaction. That's the double-entry
> invariant, and it's also my main audit/reconciliation tool later."

---

## 6. How to draw the architecture

Draw three layers, left to right or top to bottom (whiteboard shorthand, not
full UML):

```
Client -> WalletService (interface)
            -> WalletServiceImpl
                 -> AccountRepository / TransactionRepository / LedgerRepository (interfaces)
                 -> IdempotencyStore (interface)
```

Say explicitly why you're drawing interfaces for the repositories even in an
in-memory design:

> "I'm putting interfaces here even though I'll implement them in-memory,
> because it means swapping in a real database later doesn't touch a single
> line of the locking/idempotency logic — that logic is the actual hard part
> of this problem."

Then draw the two-account lock diagram (see sequence diagram #3) when you get
to concurrency — this is usually the moment that gets the most interviewer
attention, so make it visual.

---

## 7. Which interfaces to create first

In order:
1. `WalletService` — the public contract; forces you to decide the shape of
   every operation before writing any logic.
2. `AccountRepository` — needed before you can write `WalletServiceImpl` at all.
3. `IdempotencyStore` — small, and deciding its contract early (does it cache
   failures? no) shapes how you write every mutating method.
4. `TransactionRepository`, `LedgerRepository` — same reasoning as #2.

---

## 8. Which classes to implement first

1. `Money` — everything else depends on it; also a great place to narrate the
   `double`-vs-`long` decision early, since it's a near-guaranteed question.
2. `Account` — the lockable aggregate; implement `applyDebit`/`applyCredit`
   with the lock and the invariant check together, narrating both at once.
3. `Transaction`, `LedgerEntry` — simple immutable value objects, fast to write.
4. `WalletServiceImpl.deposit`/`withdraw` (single-account, no lock-ordering
   concern) — a good "prove the basic plumbing works" checkpoint before
   tackling transfer.
5. `WalletServiceImpl.transfer` (two-account, lock ordering, idempotency) — the
   centerpiece; spend real time narrating this one.
6. `reverse` — only if time allows; it's a nice-to-have that shows depth once
   the core is solid.

---

## 9. Which design patterns to introduce and when

- Introduce **Repository** the moment you write `AccountRepository` — justify
  it as "swap storage without touching logic," not "because it's a pattern."
- Introduce **Builder** only when you get to `Transaction`, and only because
  it has several optional fields — don't pre-announce it.
- Explicitly **decline** to introduce Factory/Observer/Command/Singleton if
  asked "what patterns did you use" — naming patterns you *didn't* need, and
  why, is a stronger signal than cramming in extra ones.

---

## 10. How to discuss concurrency

This is the section to slow down for. Structure it as: identify the race →
explain why a lock is needed, not a weaker tool → explain the two-account
deadlock risk → explain the fix → mention idempotency as a *separate*
concurrency concern.

> "The obvious race is two threads debiting the same account concurrently —
> that's a lock, not a CAS, because checking the balance and writing the new
> one has to be one atomic step, and for a transfer I also need the ledger
> write in that same atomic step. The less obvious race is a transfer
> involving *two* accounts: if I always lock 'source then destination,' two
> transfers going in opposite directions between the same two accounts can
> deadlock — classic circular wait. The fix is to always lock in one global
> order — say, ascending account id — regardless of which account is the
> source for this particular call. Both threads then agree on which lock to
> take first, so the deadlock is structurally impossible, not just unlikely."

If asked "how would you test that": describe the two-thread opposite-direction
test with a timeout — "if lock ordering regresses, the test doesn't fail an
assertion, it hangs until the timeout, which is itself the signal."

---

## 11. How to discuss edge cases

Have these ready, in priority order, without being asked:
1. Insufficient funds — reject without any partial mutation.
2. Self-transfer (source == destination) — reject at validation, before any lock.
3. Currency mismatch between account and amount.
4. Frozen account — blocks debits, still accepts credits (state the assumption
   out loud, since it's a real product decision, not an obvious default).
5. Duplicate request (idempotency key reused).
6. Reversing an already-reversed, or non-existent, transaction.
7. Daily KYC limit exceeded.

---

## 12. How to handle interviewer interruptions

- **"What if the interviewer asks you to add a feature mid-design?"** Don't
  restart. Say what changes and what doesn't: "Adding a transfer fee is just a
  second transfer, platform-account as destination — the primitive already
  supports it, I don't need to touch locking or idempotency." If it genuinely
  does require a model change (e.g. multi-currency), say so plainly and name
  the specific fields/classes affected rather than guessing in code live.
- **"What if they push back on a decision (e.g. 'why not use `BigDecimal`')?**
  Don't get defensive — restate it as a trade-off: "Totally fair, `BigDecimal`
  is also exact and more familiar to a lot of teams. I went with `long` minor
  units for allocation-free arithmetic and because most production ledgers I'm
  aware of use integer minor units for exactly this reason — but it's a real
  trade-off, not a correctness issue either way."
- **"What if you're asked something you genuinely don't know?"** Say what you
  *do* know adjacent to it, and reason toward an answer out loud rather than
  guessing silently — staff interviews reward visible reasoning over a lucky
  right answer.

---

## 13. How to prioritise if time is running out

Drop in this order (stop whenever time runs out — everything above the cut
line should already be solid):
1. Reversal/refund flow (nice-to-have, say so explicitly: "I'd add this next
   if we had more time — it's a new-compensating-transaction pattern, similar
   shape to transfer").
2. Account statement / ledger query API (keep the `LedgerEntry` writes, drop
   the query method if truly out of time).
3. KYC daily limits (mention the enum and the check, skip the full daily-reset
   bookkeeping if pressed).
4. Freeze/unfreeze (mention the enum states, skip implementing the methods).

**Never drop:** the overdraft invariant, the double-entry ledger write inside
the lock, and the deadlock-free lock ordering — these are the load-bearing
parts of the design; everything else is decoration.

---

## 14. Three full walkthroughs with realistic timelines

### 30-minute version
- 0–3 min: restate problem, scope (sections 1–3 above).
- 3–8 min: domain model on the whiteboard — `Account`, `Money`, `Transaction`,
  `LedgerEntry`, relationships.
- 8–12 min: `WalletService` interface + exceptions.
- 12–22 min: implement `Account.applyDebit`/`applyCredit` with the lock, then
  `WalletServiceImpl.transfer` with lock ordering — narrate the deadlock
  argument as you write it.
- 22–27 min: idempotency wrapper around `transfer`.
- 27–30 min: name what you'd add next (reversal, limits, persistence) without
  building it.

### 45-minute version
- Everything in the 30-minute version, plus:
- +8 min: implement `deposit`/`withdraw` properly (not just mentioned).
- +5 min: implement the reversal state machine for a transfer.
- +2 min: sketch 2 unit tests verbally (insufficient funds, duplicate
  idempotency key) even if you don't type them.

### 60-minute version
- Everything in the 45-minute version, plus:
- +10 min: actually write the concurrent test (two threads, opposite
  directions, assert no deadlock/no overdraft) — this is a strong closer.
- +5 min: persistence section — sketch the SQL schema and the `SELECT ... FOR
  UPDATE` equivalent of your in-process lock ordering.

---

## 15. What I would say to the interviewer

*(Natural, speakable lines — use these verbatim or adapt them; this is not
textbook language on purpose.)*

- "I'm going to model money as integer cents, not a double — floating point
  will silently corrupt a balance over enough operations, and that's a
  correctness bug I'm not willing to ship in a wallet."
- "I want every mutating call to take an idempotency key from day one — not
  because it's fancy, but because 'client retried and we double-charged them'
  is a real production incident class, and it's much cheaper to design in now
  than bolt on later."
- "The thing I actually want to spend our time on is the two-account transfer
  — that's where the interesting bug lives. Single-account deposit/withdraw is
  comparatively easy, so I'll move through those quickly."
- "I'm deliberately not building a distributed ledger or event bus here — this
  is a single-process design problem, and over-engineering it would actually
  be a worse signal than under-scoping it. Happy to talk through how it'd
  extend if you want."
- "Let me actually write the test that proves this doesn't deadlock, rather
  than just asserting it verbally — I think that's a stronger way to show the
  lock ordering is correct."
- "Good question — that's actually a real trade-off, not a clear-cut answer.
  Here's what I chose and why, and here's when I'd choose differently."
