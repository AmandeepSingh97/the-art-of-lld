package lld.vendingmachine.exceptions;

/** Raised when a slot id or product id does not exist in the inventory. */
public class ProductNotFoundException extends VendingMachineException {
    public ProductNotFoundException(String message) {
        super(message);
    }
}
