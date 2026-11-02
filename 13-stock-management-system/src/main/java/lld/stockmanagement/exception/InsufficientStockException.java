package lld.stockmanagement.exception;

import lld.stockmanagement.model.InventoryKey;

/**
 * Thrown when a requested decrement (reserve / stock-out) exceeds available
 * quantity. This is an expected, recoverable business outcome — not a bug — so
 * callers are expected to catch it and surface "out of stock" to the user rather
 * than treat it as a system failure.
 */
public class InsufficientStockException extends RuntimeException {

    private final InventoryKey inventoryKey;
    private final int requested;
    private final int available;

    public InsufficientStockException(InventoryKey inventoryKey, int requested, int available) {
        super("Insufficient stock for " + inventoryKey + ": requested=" + requested
                + " but only " + available + " available");
        this.inventoryKey = inventoryKey;
        this.requested = requested;
        this.available = available;
    }

    public InventoryKey getInventoryKey() {
        return inventoryKey;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }
}
