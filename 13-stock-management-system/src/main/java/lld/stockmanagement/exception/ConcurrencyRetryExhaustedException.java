package lld.stockmanagement.exception;

import lld.stockmanagement.model.InventoryKey;

/**
 * Thrown by the optimistic-locking ledger ({@code OptimisticVersionedInventoryLedger})
 * when a compare-and-swap retry loop fails to make progress after its configured
 * maximum number of attempts. This signals pathological contention (far more
 * concurrent writers than is realistic for a single SKU/warehouse row) rather than a
 * business error, so it is distinct from {@link InsufficientStockException}.
 */
public class ConcurrencyRetryExhaustedException extends RuntimeException {
    public ConcurrencyRetryExhaustedException(InventoryKey key, int attempts) {
        super("Gave up updating " + key + " after " + attempts
                + " optimistic-concurrency retries; contention too high");
    }
}
