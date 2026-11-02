package lld.stockmanagement.ledger;

import lld.stockmanagement.exception.InsufficientStockException;
import lld.stockmanagement.model.InventoryKey;
import lld.stockmanagement.model.InventoryLevel;

/**
 * Strategy interface for the inventory ledger — the single component responsible for
 * holding {@code available}/{@code reserved} counts per {@link InventoryKey} and
 * mutating them without ever letting {@code available} go negative (the "no
 * overselling" invariant).
 *
 * <p>This interface intentionally has exactly one job. Three interchangeable
 * implementations exist, each demonstrating a different concurrency-control strategy
 * for the same correctness contract:
 * <ul>
 *   <li>{@link OptimisticVersionedInventoryLedger} — optimistic locking with a
 *       version column / compare-and-swap on the whole row.</li>
 *   <li>{@link PessimisticLockInventoryLedger} — pessimistic per-row locking,
 *       analogous to {@code SELECT ... FOR UPDATE}.</li>
 *   <li>{@link AtomicCasInventoryLedger} — lock-free atomic counters, analogous to a
 *       single {@code UPDATE inventory SET available = available - ? WHERE available
 *       >= ?} statement.</li>
 * </ul>
 * See README "Concurrency and Thread Safety" for the full comparison and when each
 * is the right choice. {@code InventoryService} depends only on this interface
 * (Strategy pattern), so the concurrency strategy can be swapped per deployment, or
 * even per SKU hotness, without touching business logic.
 */
public interface InventoryLedger {

    /**
     * Creates a ledger row for {@code key} if one does not already exist, seeded with
     * {@code initialAvailable} and zero reserved. Idempotent: calling it again for an
     * existing key is a no-op.
     */
    void initialize(InventoryKey key, int initialAvailable);

    /** Returns the current snapshot for {@code key}, or throws if never initialized. */
    InventoryLevel snapshot(InventoryKey key);

    /** Increases available quantity (goods received). {@code quantity} must be positive. */
    InventoryLevel stockIn(InventoryKey key, int quantity);

    /**
     * Atomically moves {@code quantity} units from available to reserved.
     *
     * @throws InsufficientStockException if fewer than {@code quantity} units are
     *         available at the moment the operation commits. This is the operation
     *         where "no overselling" is enforced.
     */
    InventoryLevel reserve(InventoryKey key, int quantity);

    /** Moves {@code quantity} units from reserved back to available (order cancelled). */
    InventoryLevel releaseReservation(InventoryKey key, int quantity);

    /** Removes {@code quantity} units from reserved permanently (order shipped). */
    InventoryLevel fulfillReservation(InventoryKey key, int quantity);

    /**
     * Applies a manual delta to available quantity (cycle-count correction). Positive
     * or negative; must not take available below zero.
     */
    InventoryLevel adjust(InventoryKey key, int delta);
}
