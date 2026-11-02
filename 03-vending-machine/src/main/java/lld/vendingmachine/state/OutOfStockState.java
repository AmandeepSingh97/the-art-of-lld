package lld.vendingmachine.state;

/**
 * Engaged automatically when every slot in the inventory is empty. All
 * customer-facing operations are rejected so the machine isn't accepting
 * money it cannot fulfil orders for. Recovery happens when an admin restocks
 * at least one slot, via {@link #onInventoryReplenished}, or when the admin
 * explicitly enters maintenance mode to service the machine.
 */
public final class OutOfStockState implements VendingMachineState {

    @Override
    public String name() {
        return "OUT_OF_STOCK";
    }

    @Override
    public void enterMaintenance(VendingMachineContext ctx) {
        ctx.transitionTo(new MaintenanceState());
    }

    @Override
    public void onInventoryReplenished(VendingMachineContext ctx) {
        ctx.transitionTo(new IdleState());
    }
}
