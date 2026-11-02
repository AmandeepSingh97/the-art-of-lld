package lld.stockmanagement.ledger;

import lld.stockmanagement.exception.InvalidQuantityException;

/**
 * Shared, stateless validation helpers for {@link InventoryLedger} implementations.
 * Not an attempt to force inheritance between the three strategies — they remain
 * structurally independent (different internal storage, different locking) — this is
 * just DRY for the one bit of logic (quantity validation) that is genuinely identical
 * and has no bearing on the concurrency-control comparison.
 */
final class LedgerValidation {

    private LedgerValidation() {
    }

    static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new InvalidQuantityException("quantity must be positive, got: " + quantity);
        }
    }
}
