package lld.vendingmachine.exceptions;

/** Base type for every checked domain error the vending machine can raise. */
public class VendingMachineException extends RuntimeException {
    public VendingMachineException(String message) {
        super(message);
    }

    public VendingMachineException(String message, Throwable cause) {
        super(message, cause);
    }
}
