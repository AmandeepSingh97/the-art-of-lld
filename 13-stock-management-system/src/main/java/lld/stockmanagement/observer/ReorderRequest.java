package lld.stockmanagement.observer;

import lld.stockmanagement.model.InventoryKey;
import lld.stockmanagement.model.ReorderPolicy;

import java.time.Instant;
import java.util.Objects;

/**
 * A suggested replenishment produced by {@link ReorderAutomationListener}. This is a
 * value object representing "we think a purchase order should be raised" — it is
 * deliberately not itself a purchase order (creating and sending a PO is an
 * out-of-scope production integration; see README "Out of scope").
 */
public final class ReorderRequest {

    private final InventoryKey inventoryKey;
    private final int suggestedQuantity;
    private final Instant raisedAt;

    public ReorderRequest(InventoryKey inventoryKey, int suggestedQuantity) {
        this.inventoryKey = Objects.requireNonNull(inventoryKey, "inventoryKey");
        this.suggestedQuantity = suggestedQuantity;
        this.raisedAt = Instant.now();
    }

    public InventoryKey getInventoryKey() {
        return inventoryKey;
    }

    public int getSuggestedQuantity() {
        return suggestedQuantity;
    }

    public Instant getRaisedAt() {
        return raisedAt;
    }

    @Override
    public String toString() {
        return "ReorderRequest{" + inventoryKey + " qty=" + suggestedQuantity + " at=" + raisedAt + "}";
    }
}
