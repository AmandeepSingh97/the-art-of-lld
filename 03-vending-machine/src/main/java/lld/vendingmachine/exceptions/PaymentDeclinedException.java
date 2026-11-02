package lld.vendingmachine.exceptions;

/** Raised when the card payment gateway declines or fails to authorize a charge. */
public class PaymentDeclinedException extends VendingMachineException {
    public PaymentDeclinedException(String reason) {
        super("Card payment declined: " + reason);
    }
}
