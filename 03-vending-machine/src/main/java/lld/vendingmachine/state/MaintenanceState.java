package lld.vendingmachine.state;

/**
 * Operator-only mode: the machine is offline for customers (e.g. a technician
 * is servicing the coin mechanism or restocking) and no purchase can start or
 * continue while in it. We deliberately only allow entering maintenance from
 * {@code IdleState} (enforced by every other state's default "unsupported"
 * behavior) so a technician can never strand a customer's in-flight,
 * already-paid transaction.
 */
public final class MaintenanceState implements VendingMachineState {

    @Override
    public String name() {
        return "MAINTENANCE";
    }

    @Override
    public void exitMaintenance(VendingMachineContext ctx) {
        if (ctx.getInventory().isCompletelyEmpty()) {
            ctx.transitionTo(new OutOfStockState());
        } else {
            ctx.transitionTo(new IdleState());
        }
    }
}
