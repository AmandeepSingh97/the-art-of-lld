package lld.vendingmachine.exceptions;

import lld.vendingmachine.domain.Money;

/**
 * Raised when the cash box cannot represent the required change amount using
 * its current denomination counts. This must always abort the transaction
 * and refund the customer's inserted cash exactly as inserted — a vending
 * machine must never keep money it cannot make change for.
 */
public class InsufficientChangeException extends VendingMachineException {
    public InsufficientChangeException(Money amount) {
        super("Cannot make exact change for " + amount + " with the current cash float");
    }
}
