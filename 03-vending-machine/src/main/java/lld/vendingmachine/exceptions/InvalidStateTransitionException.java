package lld.vendingmachine.exceptions;

/**
 * Raised when an operation is attempted that the machine's current state does
 * not support (e.g. inserting a coin while in {@code MaintenanceState}, or
 * restocking while a dispense is mid-flight on that same slot).
 */
public class InvalidStateTransitionException extends VendingMachineException {
    public InvalidStateTransitionException(String operation, String currentState) {
        super("Operation '" + operation + "' is not valid in state '" + currentState + "'");
    }
}
