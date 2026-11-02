package lld.stockmanagement.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A batch/lot of a SKU received at a specific time with a specific expiry date.
 * Part of the optional batch/lot tracking extension (see
 * {@code lld.stockmanagement.lot}). A single {@link InventoryKey} (sku, warehouse)
 * can be backed by many lots, each depleted independently; the ledger in
 * {@code lld.stockmanagement.ledger} only tracks aggregate available/reserved
 * counts, while {@link lld.stockmanagement.lot.LotAllocationStrategy} decides which
 * physical lot satisfies a given withdrawal (e.g. First-Expired-First-Out).
 *
 * <p>This is a mutable record on purpose: its {@code quantity} is decremented in
 * place as units are allocated from it. It is confined to a single warehouse
 * worker/thread context in this design — see README "Concurrency and Thread Safety"
 * for why lot-level mutation is intentionally kept out of the hot concurrent path.
 */
public final class Lot {

    private final String lotId;
    private final String skuCode;
    private final LocalDate expiryDate;
    private int quantity;

    public Lot(String lotId, String skuCode, int quantity, LocalDate expiryDate) {
        this.lotId = Objects.requireNonNull(lotId, "lotId");
        this.skuCode = Objects.requireNonNull(skuCode, "skuCode");
        this.expiryDate = Objects.requireNonNull(expiryDate, "expiryDate");
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity cannot be negative");
        }
        this.quantity = quantity;
    }

    public String getLotId() {
        return lotId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public int getQuantity() {
        return quantity;
    }

    public boolean isExpired(LocalDate asOf) {
        return expiryDate.isBefore(asOf);
    }

    /** Deducts up to {@code requested} units from this lot, returning how many were taken. */
    public int deduct(int requested) {
        int taken = Math.min(requested, quantity);
        quantity -= taken;
        return taken;
    }

    @Override
    public String toString() {
        return "Lot{" + lotId + " sku=" + skuCode + " qty=" + quantity + " exp=" + expiryDate + "}";
    }
}
