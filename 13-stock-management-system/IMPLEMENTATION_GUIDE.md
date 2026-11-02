# Implementation Guide — Stock Management System

A practical, time-boxed guide to actually writing this code in an interview,
including exact compile/run/test commands verified against this repository.

---

## Which classes to write first

1. **Value objects** — `Sku`, `Warehouse`, `InventoryKey`, `InventoryLevel`.
   Fast to write, immediately unblock everything else, and demonstrate correct
   `equals`/`hashCode` discipline early.
2. **`InventoryLedger` interface** — the central Strategy contract. Write this
   before any implementation so you're designing the contract deliberately, not
   discovering it by accident while coding the first implementation.
3. **One concrete ledger** (start with `PessimisticLockInventoryLedger` — simplest
   to reason about and narrate live).
4. **`InventoryService`** — wires the ledger + minimal repositories together.
5. **Repositories** (`SkuRepository`/`WarehouseRepository` + in-memory impls) —
   small and quick.
6. **`AuditTrail`** + `StockMovement`/`MovementType`.
7. **`StockEventListener` + one listener** (`LowStockAlertListener`).
8. Remaining ledger strategies (`OptimisticVersionedInventoryLedger`,
   `AtomicCasInventoryLedger`), if time allows.
9. `ReorderAutomationListener`, lot/FEFO extension — last, both explicitly
   optional.

## A minimal compilable implementation outline (20-30 minute version)

```java
// --- model ---
public final class InventoryKey {
    private final String skuCode, warehouseCode;
    public InventoryKey(String skuCode, String warehouseCode) { ... }
    // equals/hashCode on both fields
}

public final class InventoryLevel {
    private final int available, reserved;
    public InventoryLevel(int available, int reserved) {
        if (available < 0 || reserved < 0) throw new IllegalArgumentException();
        this.available = available; this.reserved = reserved;
    }
    // getters only - immutable
}

// --- ledger: ONE strategy, pessimistic, is enough for the 20-30 min version ---
public interface InventoryLedger {
    void initialize(InventoryKey key, int qty);
    InventoryLevel snapshot(InventoryKey key);
    InventoryLevel reserve(InventoryKey key, int qty);     // the core method to nail
    InventoryLevel releaseReservation(InventoryKey key, int qty);
    InventoryLevel stockIn(InventoryKey key, int qty);
}

public final class PessimisticLockInventoryLedger implements InventoryLedger {
    private static class Row {
        final ReentrantLock lock = new ReentrantLock();
        int available, reserved;
    }
    private final Map<InventoryKey, Row> rows = new ConcurrentHashMap<>();

    public InventoryLevel reserve(InventoryKey key, int qty) {
        Row row = rows.get(key);
        row.lock.lock();
        try {
            if (row.available < qty) throw new InsufficientStockException(...);
            row.available -= qty;
            row.reserved += qty;
            return new InventoryLevel(row.available, row.reserved);
        } finally {
            row.lock.unlock();
        }
    }
    // stockIn / releaseReservation follow the same lock/mutate/unlock shape
}

// --- facade: minimal, no audit trail / observers if time is tight ---
public final class InventoryService {
    private final InventoryLedger ledger;
    public InventoryService(InventoryLedger ledger) { this.ledger = ledger; }
    public InventoryLevel reserveForOrder(InventoryKey key, int qty) {
        return ledger.reserve(key, qty);
    }
    // ... stockIn, releaseReservation delegate similarly
}
```
This compiles and demonstrates the core invariant correctly under concurrency with
nothing beyond: two value objects, one interface, one implementation, one facade.
Everything else in the full repo (audit trail, observers, lot tracking, the other
two ledger strategies) is additive on top of this skeleton.

## Suggested implementation sequence (with rough time budget for a 45-min round)

| Step | What | Minutes |
|---|---|---|
| 1 | Clarify requirements + state invariants | 5 |
| 2 | Value objects + `InventoryLedger` interface | 8 |
| 3 | `PessimisticLockInventoryLedger` (full) | 10 |
| 4 | `InventoryService` facade (reserve/release/stockIn) | 8 |
| 5 | `OptimisticVersionedInventoryLedger` core CAS loop | 8 |
| 6 | One observer (`LowStockAlertListener`) wired in | 4 |
| 7 | Narrate (or write, if time) the concurrent test | 2 |

## Important methods (the ones worth getting exactly right)

- `InventoryLedger.reserve` — the single method the entire interview question
  hinges on. Must: validate `available >= quantity` and mutate both fields in one
  atomic unit, under contention, with zero intermediate observable states.
- `InventoryService.recordAndNotify` — must run strictly *after* the ledger call
  returns successfully (never before, never if the ledger throws) — this is what
  guarantees "rejected mutation leaves zero trace."
- `OptimisticVersionedInventoryLedger.mutate` — the generic CAS-retry loop every
  other method in that class reuses; get this one method right and the other five
  methods become one-liners.
- `ReorderAutomationListener.onStockEvent` — the debounce logic
  (`putIfAbsent`/`remove` pair) is easy to get subtly wrong (e.g. forgetting the
  `remove` on restock, causing permanent suppression) — write a test for the
  restock-then-redip scenario specifically, not just "goes below once."

## Common coding mistakes (and how this repo avoids them)

- **Read-then-write race on a shared counter** (`if (available >= qty) available
  -= qty;` as two separate steps) — avoided by making every ledger mutation a
  single atomic operation (CAS or lock-guarded), never a separate check followed by
  a separate write.
- **Forgetting to unlock on an exception path** — avoided by always using
  `try { ... } finally { lock.unlock(); }`, with the exception thrown *inside* the
  `try` block before any mutation, so the lock is always released regardless of
  outcome.
- **Letting a failed mutation still produce an audit entry** — avoided by placing
  `auditTrail.record(...)` strictly after the ledger call's return value is
  obtained, never before or in a `finally`.
- **Treating `InsufficientStockException` and a real system failure
  (`ConcurrencyRetryExhaustedException`) as the same thing** — avoided by making
  them distinct exception types from the start (see FOLLOW_UP_QUESTIONS.md,
  Failure Handling #39, for why conflating them is a real production hazard).
- **Two independent atomics for available/reserved in the CAS strategy** — a subtle
  bug that *looks* correct (each individual field is "thread-safe") but introduces
  a window where neither bucket accounts for a unit — avoided by packing both
  fields into one CAS-able word (see `AtomicCasInventoryLedger`'s Javadoc).
- **Reorder-request spam** — avoided with an explicit debounce flag, not "just
  check available each time" (which would re-fire on every single event while
  still below threshold).

## Edge cases to call out explicitly while coding

- Reserving exactly all remaining stock (boundary: `available == quantity` must
  succeed, not be treated as "insufficient").
- Reserving one more than available (boundary: must fail cleanly).
- Releasing/fulfilling more than currently reserved (must not drive `reserved`
  negative — clamp or throw, pick one and be consistent; this repo throws for
  `fulfillReservation`, clamps for `releaseReservation` — be ready to justify that
  asymmetry: releasing is a corrective/recovery action so being lenient is safer,
  while over-fulfilling indicates a real caller bug worth surfacing loudly).
- Adjustment that would drive `available` negative (must throw, not silently
  floor at zero — silently flooring would hide a real cycle-count discrepancy).
- Registering a duplicate SKU/warehouse code concurrently from two threads (must
  have exactly one winner, deterministically).

## How to narrate the code while writing it

Say the invariant before writing the check: "I need available minus requested to
never go below zero, under concurrent calls — so before I write a single line, I'm
deciding this whole method has to be one atomic unit, not a check followed by a
separate write." Then narrate the specific mechanism as you type it: "I'm reading
the current packed value once, validating against that exact snapshot, and only
committing with a compare-and-swap against that same snapshot — if someone else
changed it in between, my CAS fails and I retry against fresh data, I never act on
stale data." Narrate exceptions as business decisions, not afterthoughts: "this
throws a specific exception type, not a generic one, because a monitoring system
needs to tell 'customer tried to buy something we don't have' apart from 'our
concurrency control itself is struggling.'"

## What can be safely omitted under time pressure

- Two of the three ledger strategies — implement one fully, describe the other
  two's mechanism and trade-offs verbally with a whiteboard sketch.
- The lot/FEFO extension entirely (explicitly optional in the prompt).
- `ConcurrencyRetryExhaustedException` as its own type — can collapse into a
  generic `RuntimeException` with a clear message if time is critical, while
  stating out loud that you'd split it out given more time.
- Full exception hierarchy — collapse `SkuNotFoundException`/
  `WarehouseNotFoundException` into one `NotFoundException(String what)` if typing
  time matters more than taxonomy precision.
- The full audit `StockMovement` object — a simple `List<String>` of log lines is
  an acceptable stand-in if you're out of time, as long as you say "in the real
  version this would be a structured, typed record, not a string."

## Simplified version implementable in 20-30 minutes

Exactly the "minimal compilable implementation outline" above: two value objects
(`InventoryKey`, `InventoryLevel`), one interface (`InventoryLedger`), one
implementation (`PessimisticLockInventoryLedger`), one facade method
(`InventoryService.reserveForOrder`). This alone is enough to write a meaningful
concurrent test proving no overselling — which is the actual crux of the problem —
without needing the audit trail, observers, or alternate strategies at all.

---

## Verified build and test commands (this repository)

This repo's actual source lives under:
- `src/main/java/lld/stockmanagement/...` — main sources.
- `tests/lld/stockmanagement/...` — JUnit 5 tests (package `lld.stockmanagement...`,
  directory root is `tests/`, not `tests/java/`).

### Compile and run the demo
```bash
cd 13-stock-management-system
mkdir -p .tooling/out
javac -d .tooling/out $(find src -name '*.java')
java -cp .tooling/out lld.stockmanagement.Main
```

### Compile and run the full JUnit 5 test suite
Requires a JUnit 5 Platform Console Standalone jar (any recent 1.x version works;
this repo was verified against `junit-platform-console-standalone` providing JUnit
Jupiter 5.x):
```bash
cd 13-stock-management-system
JUNIT=/path/to/junit-platform-console-standalone.jar
rm -rf .tooling/out && mkdir -p .tooling/out
javac -d .tooling/out -cp "$JUNIT" $(find src tests -name '*.java')
java -jar "$JUNIT" --class-path .tooling/out --scan-class-path
```

**Verified result (this submission):** 9 containers, 28 tests, **28 passed, 0
failed**, re-run three times consecutively with identical results (no flakiness in
the concurrency tests). Full breakdown:
- `SkuTest` — 4 tests (value-object validation/equality).
- `InventoryServiceTest` — 12 tests (facade orchestration, audit trail, both
  observers, boundary/negative cases).
- `OptimisticVersionedInventoryLedgerConcurrencyTest` — 4 tests (2 shared
  concurrency proofs + 2 strategy-specific: version monotonicity, distinct
  exception types).
- `PessimisticLockInventoryLedgerConcurrencyTest` — 2 tests (shared concurrency
  proofs).
- `AtomicCasInventoryLedgerConcurrencyTest` — 2 tests (shared concurrency proofs).
- `FefoLotAllocationStrategyTest` — 4 tests (optional lot/FEFO extension).
