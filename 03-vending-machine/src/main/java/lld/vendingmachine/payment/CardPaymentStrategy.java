package lld.vendingmachine.payment;

import lld.vendingmachine.domain.Money;
import lld.vendingmachine.exceptions.PaymentDeclinedException;
import lld.vendingmachine.state.VendingMachineContext;

/**
 * Settles a purchase via the card network for the exact price. No change is
 * ever involved because the gateway charges the precise amount — this is why
 * card payments cannot fail with {@code InsufficientChangeException}, only
 * {@code PaymentDeclinedException}.
 */
public final class CardPaymentStrategy implements PaymentStrategy {

    @Override
    public PaymentOutcome settle(Money priceDue, VendingMachineContext context) throws PaymentDeclinedException {
        String authRef = context.getCardGateway().charge(priceDue);
        return PaymentOutcome.noChange(authRef);
    }
}
