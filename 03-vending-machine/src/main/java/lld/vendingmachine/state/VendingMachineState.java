package lld.vendingmachine.state;

import lld.vendingmachine.domain.Denomination;
import lld.vendingmachine.exceptions.InsufficientChangeException;
import lld.vendingmachine.exceptions.InsufficientFundsException;
import lld.vendingmachine.exceptions.InvalidStateTransitionException;
import lld.vendingmachine.exceptions.PaymentDeclinedException;
import lld.vendingmachine.exceptions.ProductNotFoundException;
import lld.vendingmachine.exceptions.SlotOutOfStockException;

/**
 * The State pattern's Strategy-for-behavior-per-state interface. Every
 * customer/operator-facing action is a method here; each concrete state
 * overrides only the actions it legitimately supports and relies on the
 * default implementation (which throws {@link InvalidStateTransitionException})
 * for everything else. This avoids a giant switch-on-enum-state in
 * {@code VendingMachine} and keeps each state's logic co-located with the
 * state itself (Single Responsibility: one class per state).
 */
public interface VendingMachineState {

    String name();

    default void insertCoin(VendingMachineContext ctx, Denomination denomination) {
        throw new InvalidStateTransitionException("insertCoin", name());
    }

    default void selectProductForCash(VendingMachineContext ctx, String slotId)
            throws InsufficientFundsException, InsufficientChangeException,
            SlotOutOfStockException, ProductNotFoundException {
        throw new InvalidStateTransitionException("selectProductForCash", name());
    }

    default void payByCard(VendingMachineContext ctx, String slotId)
            throws PaymentDeclinedException, SlotOutOfStockException, ProductNotFoundException {
        throw new InvalidStateTransitionException("payByCard", name());
    }

    default void cancel(VendingMachineContext ctx) {
        throw new InvalidStateTransitionException("cancel", name());
    }

    default void enterMaintenance(VendingMachineContext ctx) {
        throw new InvalidStateTransitionException("enterMaintenance", name());
    }

    default void exitMaintenance(VendingMachineContext ctx) {
        throw new InvalidStateTransitionException("exitMaintenance", name());
    }

    /** Called by admin tooling after a restock to re-evaluate OutOfStock -> Idle recovery. */
    default void onInventoryReplenished(VendingMachineContext ctx) {
        // no-op by default; only OutOfStockState cares
    }
}
