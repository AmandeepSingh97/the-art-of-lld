package lld.stockmanagement.observer;

import lld.stockmanagement.model.InventoryKey;
import lld.stockmanagement.model.InventoryLevel;
import lld.stockmanagement.model.MovementType;

import java.time.Instant;

/**
 * Immutable event published by {@code InventoryService} after every successful
 * ledger mutation. This is the payload observers receive — they never touch the
 * ledger directly, which keeps {@link StockEventListener} implementations free of any
 * concurrency concerns of their own.
 */
public final class StockEvent {

    private final InventoryKey inventoryKey;
    private final MovementType type;
    private final InventoryLevel levelAfter;
    private final Instant occurredAt;

    public StockEvent(InventoryKey inventoryKey, MovementType type, InventoryLevel levelAfter) {
        this.inventoryKey = inventoryKey;
        this.type = type;
        this.levelAfter = levelAfter;
        this.occurredAt = Instant.now();
    }

    public InventoryKey getInventoryKey() {
        return inventoryKey;
    }

    public MovementType getType() {
        return type;
    }

    public InventoryLevel getLevelAfter() {
        return levelAfter;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
