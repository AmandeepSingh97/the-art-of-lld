package lld.vendingmachine.domain;

import lld.vendingmachine.exceptions.SlotOutOfStockException;

import java.util.Objects;

/**
 * A physical coil/shelf position inside the machine. A slot owns the mutable
 * quantity counter for the product it holds; this is the smallest unit of
 * state that needs protecting against concurrent mutation (a customer
 * dispensing the last unit racing an admin restock, or two dispense attempts
 * racing each other if the machine were ever extended to a multi-head
 * dispenser).
 *
 * <p>Locking granularity: each {@code Slot} synchronizes on itself for
 * quantity mutations. This is intentionally <em>slot-level</em> rather than
 * machine-level locking, so that an admin can restock slot B while a customer
 * transaction is mid-flight on slot A without contention. See README section
 * 11 (Concurrency and Thread Safety) for the full reasoning.</p>
 */
public final class Slot {

    private final String slotId;
    private final int capacity;
    private Product product;
    private int quantity;

    public Slot(String slotId, Product product, int quantity, int capacity) {
        this.slotId = Objects.requireNonNull(slotId, "slotId");
        this.product = Objects.requireNonNull(product, "product");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (quantity < 0 || quantity > capacity) {
            throw new IllegalArgumentException("quantity must be in [0, capacity]");
        }
        this.quantity = quantity;
        this.capacity = capacity;
    }

    public String getSlotId() {
        return slotId;
    }

    public synchronized Product getProduct() {
        return product;
    }

    public synchronized int getQuantity() {
        return quantity;
    }

    public int getCapacity() {
        return capacity;
    }

    public synchronized boolean isEmpty() {
        return quantity == 0;
    }

    /**
     * Atomically checks availability and decrements the count, returning the
     * dispensed product. Doing the check-then-act under a single monitor is
     * what prevents the classic TOCTOU bug where two threads both observe
     * {@code quantity == 1} and both decide it is safe to dispense.
     */
    public synchronized Product dispenseOne() {
        if (quantity == 0) {
            throw new SlotOutOfStockException(slotId);
        }
        quantity--;
        return product;
    }

    /**
     * Admin restock operation. Capacity is enforced so a restock request can
     * never silently overfill a physical coil.
     */
    public synchronized void restock(int additionalUnits) {
        if (additionalUnits < 0) {
            throw new IllegalArgumentException("additionalUnits cannot be negative");
        }
        int newQuantity = quantity + additionalUnits;
        if (newQuantity > capacity) {
            throw new IllegalArgumentException(
                    "Restocking " + additionalUnits + " into slot " + slotId
                            + " would exceed capacity " + capacity);
        }
        this.quantity = newQuantity;
    }

    /** Admin operation to swap or reprice the product assigned to this slot. */
    public synchronized void reassignProduct(Product newProduct, int newQuantity) {
        if (newQuantity < 0 || newQuantity > capacity) {
            throw new IllegalArgumentException("newQuantity must be in [0, capacity]");
        }
        this.product = Objects.requireNonNull(newProduct, "newProduct");
        this.quantity = newQuantity;
    }

    @Override
    public String toString() {
        return "Slot{" + slotId + ", product=" + product.getName()
                + ", qty=" + quantity + "/" + capacity + '}';
    }
}
