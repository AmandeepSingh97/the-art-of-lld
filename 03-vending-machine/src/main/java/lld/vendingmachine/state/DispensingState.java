package lld.vendingmachine.state;

/**
 * Transient state representing the physical dispense motor running. No
 * customer or admin operation is valid while a dispense is in flight — in a
 * real machine this would be the window guarded by the motor's hardware
 * interlock. In this synchronous in-memory model the window is as long as
 * {@code dispenseAndLog} takes, but modeling it explicitly as its own state
 * (rather than inline logic in {@code HasMoneyState}) documents the
 * invariant "nothing else may happen mid-dispense" and makes it visible to
 * anyone extending the state machine later (e.g. to an async hardware driver).
 */
public final class DispensingState implements VendingMachineState {

    @Override
    public String name() {
        return "DISPENSING";
    }
}
