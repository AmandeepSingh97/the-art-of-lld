package lld.vendingmachine.state;

import lld.vendingmachine.domain.Denomination;
import lld.vendingmachine.domain.Product;
import lld.vendingmachine.domain.Slot;
import lld.vendingmachine.exceptions.InsufficientChangeException;
import lld.vendingmachine.exceptions.InsufficientFundsException;
import lld.vendingmachine.exceptions.SlotOutOfStockException;
import lld.vendingmachine.payment.CashPaymentStrategy;
import lld.vendingmachine.payment.PaymentOutcome;

/**
 * At least one coin/bill has been inserted and not yet spent. The customer
 * may keep adding cash, attempt to buy a product, or cancel for a full refund
 * of exactly what they put in. By design this state does not support
 * {@code payByCard} — mixing cash-in-progress with a card tap is disallowed
 * to keep "what happens to the inserted cash" unambiguous; a customer who
 * wants to switch to card must cancel first.
 */
public final class HasMoneyState implements VendingMachineState {

    private static final CashPaymentStrategy CASH_STRATEGY = new CashPaymentStrategy();

    @Override
    public String name() {
        return "HAS_MONEY";
    }

    @Override
    public void insertCoin(VendingMachineContext ctx, Denomination denomination) {
        ctx.addToBalance(denomination);
    }

    @Override
    public void selectProductForCash(VendingMachineContext ctx, String slotId)
            throws InsufficientFundsException, InsufficientChangeException, SlotOutOfStockException {
        Slot slot = ctx.getInventory().getSlot(slotId);
        if (slot.isEmpty()) {
            // Per-slot stock-out: stays in HasMoneyState so the customer can pick another item.
            throw new SlotOutOfStockException(slotId);
        }

        PaymentOutcome outcome;
        try {
            outcome = CASH_STRATEGY.settle(slot.getProduct().getPrice(), ctx);
        } catch (InsufficientChangeException e) {
            // The machine cannot fulfil this purchase at all right now: abort and refund in full.
            ctx.refundInsertedCash();
            ctx.transitionTo(new IdleState());
            throw e;
        }
        // InsufficientFundsException intentionally propagates without catching here: the caller
        // (VendingMachine) surfaces it as "insert more money", and we deliberately stay in
        // HasMoneyState — no transition, no mutation of the customer's running balance.

        Product dispensed = slot.dispenseOne();
        ctx.transitionTo(new DispensingState());
        ctx.dispenseAndLog(dispensed, outcome.changeToDispense(), "CASH");
        ctx.resetBalanceAndSelection();

        if (ctx.getInventory().isCompletelyEmpty()) {
            ctx.transitionTo(new OutOfStockState());
        } else {
            ctx.transitionTo(new IdleState());
        }
    }

    @Override
    public void cancel(VendingMachineContext ctx) {
        ctx.refundInsertedCash();
        ctx.transitionTo(new IdleState());
    }
}
