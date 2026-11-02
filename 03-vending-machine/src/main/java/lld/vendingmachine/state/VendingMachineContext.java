package lld.vendingmachine.state;

import lld.vendingmachine.change.ChangeMaker;
import lld.vendingmachine.domain.CashBox;
import lld.vendingmachine.domain.Denomination;
import lld.vendingmachine.domain.Inventory;
import lld.vendingmachine.domain.Money;
import lld.vendingmachine.domain.Product;
import lld.vendingmachine.payment.CardPaymentGateway;

import java.util.List;
import java.util.Map;

/**
 * The narrow seam between the {@code state} package and {@code core.VendingMachine}.
 * Each concrete {@link VendingMachineState} only needs these operations to
 * implement its transitions — it never sees the full {@code VendingMachine}
 * facade. This is Interface Segregation in service of the State pattern: the
 * context exposes exactly the mutators/queries state classes need, nothing
 * more (so a state class cannot, say, reach into admin-only operations).
 */
public interface VendingMachineContext {

    Money getBalance();

    void addToBalance(Denomination denomination);

    List<Denomination> getInsertedDenominations();

    /** Clears both the balance and the inserted-denomination history. */
    void resetBalanceAndSelection();

    void setSelectedSlotId(String slotId);

    String getSelectedSlotId();

    Inventory getInventory();

    CashBox getCashBox();

    ChangeMaker getChangeMaker();

    CardPaymentGateway getCardGateway();

    /** Transitions the machine to a new state. The sole mutator of machine state. */
    void transitionTo(VendingMachineState newState);

    /**
     * Simulates the physical act of dispensing a product and any change,
     * and records the completed transaction for audit/receipt purposes.
     */
    void dispenseAndLog(Product product, Map<Denomination, Integer> change, String paymentDescription);

    /** Returns the exact coins/bills inserted so far to the customer (refund on cancel/failure) and clears state. */
    void refundInsertedCash();
}
