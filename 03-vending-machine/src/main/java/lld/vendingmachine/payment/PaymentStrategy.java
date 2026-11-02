package lld.vendingmachine.payment;

import lld.vendingmachine.domain.Money;
import lld.vendingmachine.exceptions.InsufficientChangeException;
import lld.vendingmachine.exceptions.InsufficientFundsException;
import lld.vendingmachine.exceptions.PaymentDeclinedException;
import lld.vendingmachine.state.VendingMachineContext;

/**
 * Strategy interface decoupling "how a payment is finalized" from the state
 * machine that drives the purchase flow. Two implementations:
 * {@link CashPaymentStrategy} (settles against previously inserted coins/bills
 * and may need to make change) and {@link CardPaymentStrategy} (delegates to
 * an external gateway for the exact amount, no change needed).
 *
 * <p>Why Strategy and not subclassing {@code VendingMachine} per payment type:
 * payment method is an orthogonal axis to machine state (you can be in
 * {@code HasMoneyState} and still choose cash-completion or, in a fancier
 * machine, tap a card mid-way) — subclassing would force a combinatorial
 * explosion of State x PaymentMethod classes.</p>
 */
public interface PaymentStrategy {

    /**
     * Attempts to settle {@code priceDue} using this strategy's mechanism.
     *
     * @throws InsufficientFundsException  (cash only) inserted balance is less than the price;
     *                                      recoverable, caller should keep waiting for more coins
     * @throws InsufficientChangeException (cash only) exact change cannot be made; caller must abort and refund
     * @throws PaymentDeclinedException    (card only) the gateway declined the charge
     */
    PaymentOutcome settle(Money priceDue, VendingMachineContext context)
            throws InsufficientFundsException, InsufficientChangeException, PaymentDeclinedException;
}
