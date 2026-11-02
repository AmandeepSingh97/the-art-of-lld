package lld.vendingmachine.exceptions;

/**
 * Raised when a specific slot has zero units left. This is a per-slot
 * condition, distinct from the machine-wide {@code OutOfStockState}, which
 * only engages when *every* slot is empty.
 */
public class SlotOutOfStockException extends VendingMachineException {
    public SlotOutOfStockException(String slotId) {
        super("Slot " + slotId + " is out of stock");
    }
}
