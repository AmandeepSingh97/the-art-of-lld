package lld.stockmanagement.ledger;

import lld.stockmanagement.exception.ConcurrencyRetryExhaustedException;
import lld.stockmanagement.exception.InsufficientStockException;
import lld.stockmanagement.model.InventoryKey;
import lld.stockmanagement.model.InventoryLevel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optimistic-locking ledger: each row is an {@link AtomicReference} holding an
 * immutable {@link InventoryLevel} (which carries a {@code version} field). A
 * mutation is a read-compute-compareAndSet loop:
 *
 * <ol>
 *   <li>Read the current {@code InventoryLevel} (including its version).</li>
 *   <li>Compute a new, validated {@code InventoryLevel} with {@code version + 1}.</li>
 *   <li>{@code compareAndSet(old, new)}. If another thread mutated the row in between,
 *       the reference will have changed and the CAS fails — retry from step 1.</li>
 * </ol>
 *
 * <p>This is the in-memory analogue of the classic relational "version column"
 * pattern: {@code UPDATE inventory SET available = ?, version = version + 1 WHERE
 * sku = ? AND warehouse = ? AND version = ?}, where an affected-row-count of zero
 * means someone else won the race and the application retries.
 *
 * <p><b>Why this is correct under contention:</b> {@code AtomicReference.compareAndSet}
 * is a single hardware CAS instruction (ultimately backed by {@code cmpxchg}/LL-SC);
 * exactly one concurrent thread can succeed per contended update, and a failed CAS
 * never partially applies — the row is either fully updated to the new,
 * fully-validated state, or untouched. Because the invariant check (does available
 * cover the requested reservation?) happens against the freshly-read row on every
 * retry, a thread can never succeed with a decision that was valid against stale
 * data.
 *
 * <p><b>Cost model:</b> under low/medium contention this is cheaper than locking
 * (no OS futex/monitor, no context switch) because almost every attempt succeeds on
 * the first try. Under very high contention on a single hot key (e.g. a flash-sale
 * SKU hammered by thousands of threads), retries compound and this strategy degrades
 * worse than a lock, because failed attempts still burn CPU re-reading and
 * re-validating. {@code maxRetries} bounds the pathological case by failing fast with
 * {@link ConcurrencyRetryExhaustedException} instead of spinning forever.
 */
public final class OptimisticVersionedInventoryLedger implements InventoryLedger {

    private final Map<InventoryKey, AtomicReference<InventoryLevel>> rows = new ConcurrentHashMap<>();
    private final int maxRetries;

    public OptimisticVersionedInventoryLedger() {
        this(1000);
    }

    public OptimisticVersionedInventoryLedger(int maxRetries) {
        if (maxRetries <= 0) {
            throw new IllegalArgumentException("maxRetries must be positive");
        }
        this.maxRetries = maxRetries;
    }

    @Override
    public void initialize(InventoryKey key, int initialAvailable) {
        rows.putIfAbsent(key, new AtomicReference<>(new InventoryLevel(initialAvailable, 0, 0L)));
    }

    @Override
    public InventoryLevel snapshot(InventoryKey key) {
        return rowFor(key).get();
    }

    @Override
    public InventoryLevel stockIn(InventoryKey key, int quantity) {
        LedgerValidation.requirePositive(quantity);
        return mutate(key, current ->
                new InventoryLevel(current.getAvailable() + quantity, current.getReserved(), current.getVersion() + 1));
    }

    @Override
    public InventoryLevel reserve(InventoryKey key, int quantity) {
        LedgerValidation.requirePositive(quantity);
        return mutate(key, current -> {
            if (current.getAvailable() < quantity) {
                throw new InsufficientStockException(key, quantity, current.getAvailable());
            }
            return new InventoryLevel(current.getAvailable() - quantity, current.getReserved() + quantity,
                    current.getVersion() + 1);
        });
    }

    @Override
    public InventoryLevel releaseReservation(InventoryKey key, int quantity) {
        LedgerValidation.requirePositive(quantity);
        return mutate(key, current -> {
            int newReserved = Math.max(0, current.getReserved() - quantity);
            int released = current.getReserved() - newReserved;
            return new InventoryLevel(current.getAvailable() + released, newReserved, current.getVersion() + 1);
        });
    }

    @Override
    public InventoryLevel fulfillReservation(InventoryKey key, int quantity) {
        LedgerValidation.requirePositive(quantity);
        return mutate(key, current -> {
            if (current.getReserved() < quantity) {
                throw new InsufficientStockException(key, quantity, current.getReserved());
            }
            return new InventoryLevel(current.getAvailable(), current.getReserved() - quantity, current.getVersion() + 1);
        });
    }

    @Override
    public InventoryLevel adjust(InventoryKey key, int delta) {
        return mutate(key, current -> {
            int newAvailable = current.getAvailable() + delta;
            if (newAvailable < 0) {
                throw new InsufficientStockException(key, -delta, current.getAvailable());
            }
            return new InventoryLevel(newAvailable, current.getReserved(), current.getVersion() + 1);
        });
    }

    private AtomicReference<InventoryLevel> rowFor(InventoryKey key) {
        AtomicReference<InventoryLevel> row = rows.get(key);
        if (row == null) {
            throw new IllegalStateException("Inventory row not initialized for " + key);
        }
        return row;
    }

    private InventoryLevel mutate(InventoryKey key, java.util.function.Function<InventoryLevel, InventoryLevel> fn) {
        AtomicReference<InventoryLevel> row = rowFor(key);
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            InventoryLevel current = row.get();
            InventoryLevel updated = fn.apply(current); // may throw business exception; propagate as-is
            if (row.compareAndSet(current, updated)) {
                return updated;
            }
            // CAS failed: another thread updated the row first. Retry with fresh data.
        }
        throw new ConcurrencyRetryExhaustedException(key, maxRetries);
    }
}
