package lld.vendingmachine.admin;

import lld.vendingmachine.core.VendingMachine;
import lld.vendingmachine.domain.CashBox;
import lld.vendingmachine.domain.Denomination;
import lld.vendingmachine.domain.Money;
import lld.vendingmachine.domain.Slot;

import java.util.Map;

/**
 * Operator/technician-facing operations, intentionally kept out of
 * {@code VendingMachine}'s customer-facing API surface (Interface
 * Segregation again: a keypad UI should not even compile against
 * {@code restock}/{@code collectCash}).
 *
 * <p>Restocking deliberately does <em>not</em> require the machine to be in
 * {@code MaintenanceState}: a {@link Slot}'s quantity has its own lock (see
 * {@link Slot}), so an admin topping up slot B is safe even while a customer
 * is mid-purchase on slot A. Only operations that affect the shared
 * front-panel state (entering/exiting maintenance) go through the
 * {@code machineLock}-guarded {@code VendingMachine} methods.</p>
 */
public final class AdminService {

    private final VendingMachine machine;

    public AdminService(VendingMachine machine) {
        this.machine = machine;
    }

    /** Adds {@code additionalUnits} to a slot and lets the state machine recover from OutOfStock if needed. */
    public void restock(String slotId, int additionalUnits) {
        Slot slot = machine.getInventoryView().getSlot(slotId);
        slot.restock(additionalUnits);
        machine.notifyInventoryReplenished();
    }

    public void reprice(String slotId, Money newPrice) {
        machine.getInventoryView().getSlot(slotId).getProduct().repriceTo(newPrice);
    }

    public void loadCashFloat(Denomination denomination, int count) {
        machine.getCashBoxView().load(denomination, count);
    }

    /**
     * Collects all cash above the given float, leaving {@code floatToKeep} behind
     * so the machine can keep making change for future customers.
     */
    public Map<Denomination, Integer> collectCash(Map<Denomination, Integer> floatToKeep) {
        return machine.getCashBoxView().collectExcept(floatToKeep);
    }

    public void enterMaintenance() {
        machine.enterMaintenance();
    }

    public void exitMaintenance() {
        machine.exitMaintenance();
    }
}
