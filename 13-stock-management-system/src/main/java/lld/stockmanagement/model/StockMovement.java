package lld.stockmanagement.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable, append-only audit record of a single inventory mutation.
 *
 * <p>Audit trails must never be mutated after creation — that is the entire point of
 * an audit trail — so every field is final and the class exposes no setters. The
 * {@code resultingAvailable}/{@code resultingReserved} fields capture the
 * post-mutation state so the trail is self-sufficient for reconciliation without
 * needing to replay deltas in order.
 */
public final class StockMovement {

    private final String movementId;
    private final InventoryKey inventoryKey;
    private final MovementType type;
    private final int quantity;
    private final int resultingAvailable;
    private final int resultingReserved;
    private final String referenceId;
    private final Instant occurredAt;

    public StockMovement(InventoryKey inventoryKey, MovementType type, int quantity,
                          int resultingAvailable, int resultingReserved, String referenceId) {
        this.movementId = UUID.randomUUID().toString();
        this.inventoryKey = Objects.requireNonNull(inventoryKey, "inventoryKey");
        this.type = Objects.requireNonNull(type, "type");
        this.quantity = quantity;
        this.resultingAvailable = resultingAvailable;
        this.resultingReserved = resultingReserved;
        this.referenceId = referenceId;
        this.occurredAt = Instant.now();
    }

    public String getMovementId() {
        return movementId;
    }

    public InventoryKey getInventoryKey() {
        return inventoryKey;
    }

    public MovementType getType() {
        return type;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getResultingAvailable() {
        return resultingAvailable;
    }

    public int getResultingReserved() {
        return resultingReserved;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public String toString() {
        return "StockMovement{" + type + " qty=" + quantity + " key=" + inventoryKey
                + " -> available=" + resultingAvailable + ", reserved=" + resultingReserved
                + ", ref=" + referenceId + ", at=" + occurredAt + "}";
    }
}
