# Interview Playbook — Stock Management System

A speakable, time-boxed playbook for running this problem live in a 30/45/60-minute
Staff-level interview round.

---

## 1. How to open the discussion

Start by restating the problem in your own words and immediately surfacing the two
or three things that make it non-trivial, so the interviewer knows you see past the
CRUD surface:

> "So this is warehouse inventory management — tracking how many units of each
> product we have per warehouse, handling goods coming in and orders reserving/
> shipping stock, with an audit trail and some alerting. The part I want to get
> right early is the distinction between stock that's physically on the shelf
> versus stock that's already promised to an order but not shipped yet — and then,
> since multiple orders can try to grab the same SKU at the same time, making sure
> we never oversell. I'll sketch the core model, then come back to the concurrency
> question in detail since that's usually the crux of this one."

## 2. First 5 clarifying questions to ask

1. "Do we need a true 'reserved' state between order placement and shipment, or
   does placing an order just directly decrement available stock?"
2. "Can stock ever go negative — i.e. do we support backorders — or is 'never
   oversell' a hard invariant?"
3. "Is inventory tracked per warehouse independently, or is there some cross-
   warehouse sourcing/allocation logic I need to worry about?"
4. "Should I assume single-process/in-memory for this exercise, or do you want me
   to talk about how this would work across multiple service instances?"
5. "Do you want me to cover batch/lot tracking and expiry, or keep this to
   aggregate quantities?"

## 3. Requirements to establish

Before writing a line of code, state back the essential functional requirements
(SKU/warehouse catalog, available vs reserved, stock-in/reserve/release/fulfill,
audit trail) and the two non-functional requirements that shape everything else:
**no overselling under concurrency**, and **per-key granularity** (one hot SKU must
not slow down unrelated SKUs). Explicitly park demand forecasting, distributed
coordination, and purchase-order integration as out of scope, stating why.

## 4. How to identify core entities

Walk from the business language to objects: "a SKU is a product variant", "a
warehouse is a location", "for every (SKU, warehouse) pair, I need a number that's
available and a number that's reserved — that pairing is itself worth its own small
object since it's the thing I'll be locking on." Then: "every mutation is an event
I need to keep — that's my audit trail entity." Then: "alerting and reordering are
reactions to those events — that smells like Observer."

## 5. How to explain class relationships

> "`InventoryService` is a facade — it's the only thing the rest of the app talks
> to. It holds a repository for the catalog, an `InventoryLedger` for the actual
> counters, an audit trail, and a list of listeners. The ledger is an interface on
> purpose — I'm going to show you three implementations of it with different
> concurrency strategies, and the service doesn't need to know which one is
> plugged in."

## 6. How to draw the architecture

Draw three layers top to bottom on the whiteboard: (1) catalog (`Sku`,
`Warehouse`, repositories) — small, rarely-changing; (2) the ledger — the
"hot" layer, one interface, three boxes beside it labeled optimistic/pessimistic/
atomic-CAS; (3) the audit trail and observers hanging off the service as
side-effects of every mutation. Draw the key composite `InventoryKey` as the thing
that flows between all three layers — it's the one id that partitions everything.

## 7. Which interfaces to create first

1. `InventoryLedger` — the central contract everything else depends on.
2. `StockEventListener` — small, obvious, unblocks talking about Observer early.
3. `SkuRepository` / `WarehouseRepository` — simple, gets catalog out of the way.
4. `AuditTrail` — simple, append-only.

## 8. Which classes to implement first

1. Value objects: `Sku`, `Warehouse`, `InventoryKey`, `InventoryLevel` — these are
   quick wins that unblock everything else and show you take equals/hashCode and
   immutability seriously.
2. One concrete `InventoryLedger` — start with the **pessimistic** one; it's the
   easiest to get right under time pressure and the easiest to explain live
   ("I lock the row, mutate, unlock"). Add the optimistic and CAS ones after, time
   permitting, explicitly as "here's the alternative, and here's the trade-off."
3. `InventoryService` tying it together.
4. One observer (`LowStockAlertListener`) to prove the Observer wiring works.
5. Audit trail.
6. (Time permitting) Reorder automation, lot/FEFO extension.

## 9. Which design patterns to introduce and when

Introduce **Strategy** the moment you define `InventoryLedger` — say explicitly
"this is a Strategy, because I'm about to show you three implementations with the
same contract." Introduce **Observer** when you mention alerting/reordering, not
before — don't pre-announce patterns you haven't earned yet. Introduce **Facade**
when you draw `InventoryService` — "this is a facade so callers don't need to
sequence ledger-then-audit-then-notify themselves."

## 10. How to discuss concurrency

This is the section to spend real time on. Sequence:
1. State the invariant: "available must never go negative, even under concurrent
   reservations for the same SKU."
2. Show the broken naive version on the whiteboard (`if (available >= qty)
   available -= qty;`) and name the race explicitly: "two threads can both pass the
   check before either writes."
3. Present the pessimistic fix first (easiest to explain: "lock the row, check,
   mutate, unlock — SELECT FOR UPDATE in DB terms").
4. Present the optimistic fix second: "read, compute a new version, CAS it in; if
   someone beat me to it, retry with fresh data — this is the version-column
   pattern."
5. Present the atomic-CAS-counter fix third, and proactively raise the subtlety:
   "if I use two separate atomic integers for available and reserved, there's a
   window where a reservation has decremented available but not yet incremented
   reserved — so I pack both into one atomic long and CAS the whole thing at once."
6. Compare cost models out loud: optimistic is cheapest when contention is low,
   degrades under very high contention because failed attempts still burn CPU;
   pessimistic never wastes work but serializes everything on the lock; atomic CAS
   is cheapest per-op but least extensible if you ever need a third coupled field.
7. State lock granularity explicitly: "all three partition by (SKU, warehouse) key
   — I never take a global lock, so one hot SKU never blocks another."

## 11. How to discuss edge cases

- Reservation exceeding available stock — must fail with zero side effects (no
  audit entry, no listener notification).
- Fulfilling more than reserved — same shape of failure.
- Two threads racing to register the same SKU code.
- A caller crashing after a successful reservation but before confirming the order
  — explain this is *why* reserved is a distinct state (the stock isn't lost, it's
  recoverable via `releaseReservation`, and in production a TTL-based reconciliation
  job would clean these up).
- Low-stock alert vs reorder-request: alert should keep firing while stock stays
  low (ops needs to stay aware), but reorder requests must be debounced (don't
  spam purchasing with duplicate POs).

## 12. How to handle interviewer interruptions

If interrupted with "what if two warehouses need to be locked at once for a
transfer?" — acknowledge it's a real extension, state the answer briefly (lock-
ordering discipline, e.g. always lock the lexicographically smaller key first to
avoid deadlock), and explicitly say you're parking it since it's not in the current
scope, then return to the thread you were on. Don't let a tangent derail the main
narrative — name it, answer briefly, bookmark it, continue.

If interrupted with "why not just use a database for this?" — agree it's exactly
what you'd do in production, briefly map the three strategies onto SQL (version
column, SELECT FOR UPDATE, single atomic UPDATE statement), and note that the
in-memory version here is structurally identical so the ledger implementation is a
drop-in replacement later.

## 13. How to prioritise if time is running out

Protect, in this order: (1) a single correct, compiling `InventoryLedger`
implementation with the core invariant provably enforced — even if it's "just" the
pessimistic one; (2) the facade wiring reserve/release/fulfill/stock-in correctly;
(3) one observer wired end-to-end to show the pattern. Sacrifice first: the
optional lot/FEFO extension, the second and third ledger strategies (describe them
verbally instead of coding all three), exhaustive exception types (collapse to
fewer, broader exceptions if needed).

## 14. Three full walkthroughs with realistic timelines

### 30-minute walkthrough
- 0–5 min: clarify requirements, state the core invariant and in/out of scope.
- 5–10 min: sketch entities (`Sku`, `Warehouse`, `InventoryKey`, `InventoryLevel`)
  and the `InventoryLedger` interface.
- 10–20 min: implement **one** ledger strategy (pessimistic, `ReentrantLock` per
  row) fully, narrating the invariant check as you write it.
- 20–26 min: wire a minimal `InventoryService.reserveForOrder`/`stockIn`, verbally
  describe audit trail and one observer rather than fully coding them.
- 26–30 min: state how you'd prove no-overselling with a concurrent test, and
  briefly compare it to the optimistic/CAS alternatives verbally.

### 45-minute walkthrough
- 0–7 min: requirements + entities, as above.
- 7–12 min: `InventoryLedger` interface + `InventoryService` facade skeleton.
- 12–25 min: implement pessimistic ledger fully; implement optimistic ledger's
  core CAS loop, explaining the retry semantics.
- 25–32 min: implement `InventoryService` reserve/release/fulfill/stock-in with
  audit recording.
- 32–38 min: implement one observer (low-stock alert) end-to-end.
- 38–45 min: write (or at least fully narrate, if typing time is short) the
  concurrent no-overselling test against one strategy; discuss the atomic-CAS
  approach and its packing trick verbally with a whiteboard sketch.

### 60-minute walkthrough
- 0–8 min: requirements, invariants, entities.
- 8–15 min: interfaces (`InventoryLedger`, `StockEventListener`, repositories,
  `AuditTrail`).
- 15–30 min: implement all three ledger strategies (pessimistic, optimistic,
  atomic-CAS), explicitly comparing them as you go.
- 30–40 min: `InventoryService` facade with full reserve/release/fulfill/stock-
  in/adjust, audit trail wiring.
- 40–48 min: both observers (low-stock alert, reorder automation with debounce).
- 48–58 min: write the concurrent multi-threaded test proving no overselling
  against at least one strategy (ideally mention/sketch running it against all
  three via a shared test base, as this repo does).
- 58–60 min: discuss production evolution (persistence mapping, distributed
  extension, idempotency) briefly to show you know where the line is.

## 15. What I would say to the interviewer

- "I'm going to model available and reserved as two separate counters rather than
  one, because cancellations need somewhere to put stock back without re-deriving
  history."
- "The thing I want to get right here isn't the CRUD — it's making sure that if
  fifty threads try to grab the last unit of a hot SKU at the same time, exactly
  one of them wins and nobody oversells. Let me show you three ways to guarantee
  that, and talk about when I'd pick each."
- "I'm deliberately not building a distributed lock or a message queue here — this
  is in-process, and adding that machinery would be solving a problem we don't
  have yet."
- "This exception here — insufficient stock — is a normal business outcome, not a
  bug. I want it to be a different type from 'my retry loop gave up,' because one
  of those pages someone on-call and the other doesn't."
- "I packed both counters into one atomic long here instead of using two separate
  atomics, because with two separate atomics there's a window where a thread could
  see units that exist in neither bucket — let me show you why that matters."
- "I'm treating the low-stock alert and the reorder suggestion differently on
  purpose — one should nag you every time, the other should only ask once until
  you've actually restocked."
- "If this had to run across multiple machines, I'd move this ledger behind a real
  database row with exactly the same update pattern I just wrote — the interface
  doesn't change, only what's behind it."
