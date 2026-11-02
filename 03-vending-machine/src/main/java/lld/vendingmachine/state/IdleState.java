package lld.vendingmachine.state;

import lld.vendingmachine.domain.Denomination;
import lld.vendingmachine.domain.Product;
import lld.vendingmachine.domain.Slot;
import lld.vendingmachine.exceptions.PaymentDeclinedException;
import lld.vendingmachine.exceptions.SlotOutOfStockException;
import lld.vendingmachine.payment.CardPaymentStrategy;
import lld.vendingmachine.payment.PaymentOutcome;

/**
 * The machine's resting state: no money inserted, nothing selected. Accepts
 * the first coin (which bumps it to {@link HasMoneyState}) or a direct card
 * tap-and-select (a single atomic card purchase with no coin accumulation
 * phase, which is how real modern tap-to-pay vending machines behave).
 */
public final class IdleState implements VendingMachineState {

    private static final CardPaymentStrategy CARD_STRATEGY = new CardPaymentStrategy();

    @Override
    public String name() {
        return "IDLE";
    }

    @Override
    public void insertCoin(VendingMachineContext ctx, Denomination denomination) {
        ctx.addToBalance(denomination);
        ctx.transitionTo(new HasMoneyState());
    }

    @Override
    public void payByCard(VendingMachineContext ctx, String slotId) throws PaymentDeclinedException {
        Slot slot = ctx.getInventory().getSlot(slotId);
        if (slot.isEmpty()) {
            throw new SlotOutOfStockException(slotId);
        }
        // Charge is attempted BEFORE dispensing, and dispensing only happens on success:
        // this guarantees we never give out a product without being paid for it.
        PaymentOutcome outcome = CARD_STRATEGY.settle(slot.getProduct().getPrice(), ctx);

        Product dispensed = slot.dispenseOne();
        ctx.transitionTo(new DispensingState());
        ctx.dispenseAndLog(dispensed, outcome.changeToDispense(), "CARD:" + outcome.authorizationReference());

        if (ctx.getInventory().isCompletelyEmpty()) {
            ctx.transitionTo(new OutOfStockState());
        } else {
            ctx.transitionTo(new IdleState());
        }
    }

    @Override
    public void enterMaintenance(VendingMachineContext ctx) {
        ctx.transitionTo(new MaintenanceState());
    }
}
