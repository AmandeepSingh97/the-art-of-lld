# Interview Playbook — Vending Machine

A speakable, time-boxed playbook for driving this design live in a 30/45/60
minute round.

---

## 1. How to open the discussion

Start by restating the problem in one sentence and immediately proposing the
scope boundary, so you're not guessing at what the interviewer wants for the
next ten minutes:

> "A vending machine has slots of products, accepts cash or card, dispenses a
> product plus change, and an operator can restock it. I'll design the
> software for one physical machine — a customer-facing purchase flow plus an
> admin flow — and I'll explicitly call out where I'm assuming something so
> you can correct me early."

Then immediately move to clarifying questions — don't start naming classes
yet.

## 2. First 5 clarifying questions to ask

1. "Does the machine need to make change, or is it exact-cash-only?" —
   determines if you need a `CashBox`/`ChangeMaker` at all.
2. "Can a customer mix cash and card in one purchase?" — determines if
   payment is one strategy per transaction or a running ledger.
3. "Is a product tied to exactly one slot, or can one product span multiple
   slots?" — determines your inventory lookup shape.
4. "What counts as 'out of stock' — one empty slot, or the whole machine?
   Should the machine stop accepting money when it's completely empty?" —
   this directly decides whether OutOfStock is a real top-level state or just
   a per-slot error.
5. "Should I think about concurrent access to one machine, or is the
   interesting concurrency actually at a fleet/backend level?" — ask this
   explicitly; it's the single most differentiating question in this
   problem and interviewers notice when a candidate asks it versus assumes.

## 3. Requirements to establish

Say out loud, fast, before designing:
- Functional: insert cash, insert/tap card, select product, dispense +
  change, cancel + refund, restock, reprice, collect cash, maintenance mode.
- Non-functional: correctness under concurrent admin-vs-customer access,
  money must never be lost or duplicated, no floating-point money, clear
  failure/refund behavior.

## 4. How to identify core entities

Walk it top-down from "what does the machine contain" to "what does a
purchase touch":
- *Nouns in the problem statement* → candidate entities: Product, Slot,
  Inventory, Money, CashBox, Payment, Transaction.
- *Verbs* → candidate operations/services: insertCoin, selectProduct, pay,
  dispense, restock. If a verb needs its own varying implementations (cash
  vs card pay), that's a Strategy, not just a method.
- *Lifecycle* → the state machine: say "the machine's legal operations
  change based on where it is in a purchase — that's a State pattern
  candidate," and list the states as you derive them from the problem
  (Idle, HasMoney, Dispensing, OutOfStock, Maintenance) rather than reciting
  them from memory.

## 5. How to explain class relationships

Speak in ownership terms, not UML jargon first:
> "`VendingMachine` owns one `Inventory` and one `CashBox` — they don't
> outlive the machine, so that's composition. A `Slot` points at a
> `Product`, but two slots can point at the same product, so that's an
> association, not ownership — the product's lifecycle isn't tied to one
> slot."

Then, if asked to formalize: composition = solid diamond (Inventory→Slot),
association = plain arrow (Slot→Product), implements = dashed arrow with
hollow triangle (state classes → `VendingMachineState`).

## 6. How to draw the architecture

Draw in this order so the diagram builds up logically on the whiteboard:
1. Box: `VendingMachine` (facade) in the middle.
2. Around it: `Inventory`, `CashBox`, `TransactionLog` as composed boxes.
3. A separate cluster: `VendingMachineState` interface with 5 small boxes
   hanging off it (Idle/HasMoney/Dispensing/OutOfStock/Maintenance), with an
   arrow from `VendingMachine` to "currentState."
4. A separate cluster: `PaymentStrategy` interface with Cash/Card boxes,
   each pointed to from the state classes that use them.
5. Last: `ChangeMaker` hanging off `CashPaymentStrategy`.
Narrate while drawing: "Three independent clusters — lifecycle, payment
mechanics, and change math — each swappable without touching the others."

## 7. Which interfaces to create first

In this order, and say why each one *before* writing it:
1. `VendingMachineState` — "this is the backbone; everything else plugs into
   it."
2. `PaymentStrategy` — "payment mechanics vary independently of state."
3. `ChangeMaker` — "smallest, most isolated piece of variability; easy win
   to show Open/Closed."
4. `CardPaymentGateway` — "external dependency boundary; must be mockable."

## 8. Which classes to implement first

1. `Money` (tiny, no dependencies, sets the tone for "no floats").
2. `Denomination`, `Product`, `Slot`, `Inventory` (domain skeleton).
3. `GreedyChangeMaker` + a quick inline test by hand ("85 cents → 3 quarters
   1 dime").
4. `CashBox`.
5. `VendingMachineContext` + `IdleState`/`HasMoneyState` (the two states that
   carry 90% of the logic).
6. `VendingMachine` wiring it together.
7. `DispensingState`/`OutOfStockState`/`MaintenanceState` (small, mechanical
   once the pattern is established).
8. `AdminService` last — it's thin and depends on everything else existing.

## 9. Which design patterns to introduce and when

- Mention **State** the moment you say "legal operations change based on
  where we are in a purchase" (should happen within the first 5 minutes of
  modeling).
- Mention **Strategy** when you get to "how does a cash payment differ from
  a card payment" — don't introduce it before you have a concrete reason.
- Mention **Facade** only once you have more than 2-3 collaborating classes
  and need to say "here's the one thing a caller talks to."
- Explicitly say what you're *not* using and why (no Singleton, no Factory)
  if time allows — it signals judgment, not just pattern recall.

## 10. How to discuss concurrency

Don't jump straight to "I'll use a lock." Walk the reasoning:
1. "First — is concurrency even real here? A single machine's keypad is
   physically one-session-at-a-time, so I don't need fine-grained locking
   for front-panel ops." (shows judgment, avoids over-engineering)
2. "But it's not *zero* concurrency: an admin console or remote restock
   request can touch inventory/cash while a customer transaction is live."
   (shows you didn't just stop at "single-threaded, done")
3. "So: one coarse lock for the front panel, separate fine-grained locks on
   `Slot` and `CashBox` for the pieces admin tooling touches independently."
4. If pushed: name the specific race (two threads both see "last unit
   available," both try to dispense) and show the `synchronized`
   check-then-act fix.
5. If pushed further: name the deadlock-avoidance argument (one-directional
   lock order: front panel lock is never acquired while holding a
   `Slot`/`CashBox` lock).

## 11. How to discuss edge cases

Have these ready, in priority order:
1. Selecting an out-of-stock slot (recoverable, no state change).
2. Insufficient funds (recoverable, no state change, no refund needed —
   nothing failed, just wait for more money).
3. Insufficient change — the one true "must abort and refund" case; explain
   *why* this one is different from insufficient funds (the machine would
   otherwise keep money it can't make change for).
4. Whole-machine out of stock — a state, not just an error.
5. Entering maintenance mid-transaction — must be blocked.
6. (If time remains) Mid-dispense crash — name it as a known gap and how
   you'd close it in production (durable "payment settled, dispense
   pending" write before the physical dispense).

## 12. How to handle interviewer interruptions

- If asked "why not use a hashmap/switch instead of the State pattern":
  answer with the 5×7 combinatorial argument (5 states, 7 operations) and
  "each state's rules stay co-located and the compiler forces me to think
  about every state when I add an operation" rather than reciting GoF.
- If asked "why cents instead of BigDecimal": give the real trade-off (§16
  in README) — don't just defend your choice, acknowledge BigDecimal is the
  right call for multi-currency and say you chose cents because the scope
  here is single, fixed-minor-unit currency.
- If challenged on "your lock is too coarse": restate the physical-reality
  argument, then immediately show you've *already* split out finer-grained
  locks where it matters (Slot, CashBox) — this shows the pushback was
  anticipated, not news to you.
- If the interviewer changes a requirement mid-design (e.g. "now support
  multiple currencies"): say what breaks (`Money`'s cents representation)
  and what doesn't (the whole state machine and payment strategy interfaces
  are currency-agnostic) — this demonstrates you understand your own
  design's blast radius.

## 13. How to prioritise if time is running out

Drop in this order (cheapest to cut, costs least credibility, first):
1. `AdminService` details beyond "restock exists" — just gesture at it.
2. `TransactionLog`/audit trail — mention it exists, don't implement fully.
3. `MaintenanceState`/`OutOfStockState` — describe the transitions verbally,
   implement only if time allows; these are mechanically simple once
   `HasMoneyState` exists.
4. The DP change-making fallback — describe it, don't implement it; the
   greedy version with its documented assumption is enough to show rigor.
**Never cut:** the State pattern skeleton, `Money`, the cash-purchase happy
path, and the concurrency discussion — these are what's actually being
evaluated.

## 14. Three full walkthroughs with realistic timelines

### 30-minute version
- 0–5 min: problem restatement + 5 clarifying questions.
- 5–10 min: entities + state list, drawn on the board.
- 10–20 min: implement `Money`, `Slot`, `HasMoneyState.selectProductForCash`
  happy path (cash purchase + change) end to end, narrating as you go.
- 20–25 min: concurrency discussion (verbal — the "is it even real"
  argument + where the real lock goes), no need to write the lock code if
  time is short.
- 25–30 min: name 2-3 edge cases verbally (insufficient change, out of
  stock) and what production would add.

### 45-minute version
- 0–5 min: restatement + clarifying questions.
- 5–12 min: entities, relationships, full class-diagram sketch.
- 12–30 min: implement `Money`, `Slot`, `Inventory`, `CashBox`,
  `GreedyChangeMaker`, `CashPaymentStrategy`, `HasMoneyState` — the full
  cash-purchase-with-change path, actually compiling in your head/on paper.
- 30–38 min: concurrency — write the `synchronized` on `Slot.dispenseOne`
  and the `CashBox` lock, explain the lock-splitting rationale.
- 38–45 min: card payment (briefly, it's simpler), out-of-stock state,
  and wrap with trade-offs (`BigDecimal` vs cents, greedy vs DP).

### 60-minute version
- 0–5 min: restatement + clarifying questions.
- 5–15 min: full domain model + all 5 diagrams' worth of content verbally
  (component, class, state, two sequences).
- 15–40 min: implement the full happy path for both cash and card, plus the
  two abort paths (insufficient change refund, card decline) — enough code
  that someone could run it.
- 40–50 min: concurrency deep dive with the actual lock code, deadlock
  argument, and a quick sketch of the concurrent-access test.
- 50–57 min: admin flow (restock recovering from OutOfStock) + maintenance
  mode guard.
- 57–60 min: production evolution — name persistence, monitoring, and the
  fleet-vs-single-machine distinction as deliberately out of scope.

## 15. What I would say to the interviewer

- "Before I draw anything, let me ask a few questions, because the answers
  change whether I even need a change-making algorithm."
- "I want to model this as a state machine — the set of things you're
  allowed to do really does change depending on whether you've got money in
  or not, so a State pattern keeps that honest instead of me writing a pile
  of `if` checks."
- "I'm going to use cents as a long instead of BigDecimal — it's simpler and
  exactly as correct here since we've got one fixed-minor-unit currency; if
  you want multi-currency later that's the one place I'd go back and
  change."
- "Here's the thing I actually want to flag myself: is concurrent access to
  one machine even realistic? My answer is — mostly no for the front panel,
  but yes for admin-versus-customer, so I'm putting one coarse lock on the
  customer-facing calls and finer locks on the slot and cash box
  specifically for that reason, not as a reflex."
- "If the change-making can't be satisfied, I don't want the machine to just
  keep the money — I'll make sure that path refunds exactly what was put in
  and aborts cleanly, because a machine that silently keeps money it
  couldn't make change for is a real-world support nightmare, not just an
  edge case."
- "I'm intentionally not building a database or a distributed fleet
  coordinator here — one machine's state fits comfortably in memory, and I'd
  rather spend the time getting the concurrency and failure handling right
  than add infrastructure the problem doesn't need."
- "If I had another hour, the next thing I'd build is a durable write of
  'payment settled, dispense pending' before the physical dispense, so a
  crash mid-dispense doesn't lose track of money that was already taken."
