package lld.vendingmachine.exceptions;

import lld.vendingmachine.domain.Money;

/**
 * Raised when the currently inserted cash balance is less than the selected
 * product's price. This is a recoverable condition: the machine stays in
 * {@code HasMoneyState} and simply waits for more coins, it does not abort
 * the transaction.
 */
public class InsufficientFundsException extends VendingMachineException {
    private final Money shortfall;

    public InsufficientFundsException(Money shortfall) {
        super("Insufficient funds, shortfall = " + shortfall);
        this.shortfall = shortfall;
    }

    public Money getShortfall() {
        return shortfall;
    }
}
