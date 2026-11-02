package lld.vendingmachine.payment;

import lld.vendingmachine.domain.Denomination;
import lld.vendingmachine.domain.Money;
import lld.vendingmachine.exceptions.InsufficientChangeException;
import lld.vendingmachine.exceptions.InsufficientFundsException;
import lld.vendingmachine.state.VendingMachineContext;

import java.util.List;
import java.util.Map;

/**
 * Settles a purchase using cash the customer has already inserted
 * (accumulated in {@link VendingMachineContext#getBalance()} /
 * {@link VendingMachineContext#getInsertedDenominations()}).
 *
 * <p>Sequencing matters here and is deliberately documented:
 * <ol>
 *   <li>Verify the inserted balance covers the price. If not, throw
 *       {@link InsufficientFundsException} — recoverable, no state mutated.</li>
 *   <li>Commit (deposit) the inserted cash into the {@code CashBox}. Only
 *       after this point is the money "the machine's."</li>
 *   <li>Attempt to withdraw change for (balance - price) from the box, which
 *       now includes the just-deposited cash. If this fails, the deposit is
 *       rolled back via {@code reverseDeposit} and {@link InsufficientChangeException}
 *       propagates so the caller can refund the customer's exact inserted cash.</li>
 * </ol>
 * This ordering guarantees the box's counts never reflect money that was
 * ultimately refunded, and it never leaves the customer's cash in limbo.</p>
 */
public final class CashPaymentStrategy implements PaymentStrategy {

    @Override
    public PaymentOutcome settle(Money priceDue, VendingMachineContext context)
            throws InsufficientFundsException, InsufficientChangeException {
        Money balance = context.getBalance();
        if (balance.compareTo(priceDue) < 0) {
            throw new InsufficientFundsException(priceDue.subtract(balance));
        }

        Money changeOwed = balance.subtract(priceDue);
        List<Denomination> inserted = List.copyOf(context.getInsertedDenominations());
        context.getCashBox().deposit(inserted);

        if (changeOwed.isZero()) {
            return PaymentOutcome.noChange(null);
        }

        try {
            Map<Denomination, Integer> change = context.getCashBox()
                    .withdrawForChange(changeOwed, context.getChangeMaker());
            return new PaymentOutcome(change, null);
        } catch (InsufficientChangeException e) {
            // Roll back the deposit so the box stays consistent with "what we actually kept".
            context.getCashBox().reverseDeposit(inserted);
            throw e;
        }
    }
}
