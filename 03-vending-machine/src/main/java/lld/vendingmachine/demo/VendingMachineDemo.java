package lld.vendingmachine.demo;

import lld.vendingmachine.admin.AdminService;
import lld.vendingmachine.change.GreedyChangeMaker;
import lld.vendingmachine.core.VendingMachine;
import lld.vendingmachine.domain.CashBox;
import lld.vendingmachine.domain.Denomination;
import lld.vendingmachine.domain.Inventory;
import lld.vendingmachine.domain.Money;
import lld.vendingmachine.domain.Product;
import lld.vendingmachine.domain.Slot;
import lld.vendingmachine.exceptions.InsufficientFundsException;
import lld.vendingmachine.payment.MockCardPaymentGateway;

/**
 * Runnable demonstration exercising the main customer flows end to end:
 * cash purchase with change, insufficient funds, card purchase, cancel with
 * refund, and an admin restock recovering the machine from OutOfStock.
 */
public final class VendingMachineDemo {

    public static void main(String[] args) {
        Inventory inventory = new Inventory();
        inventory.addSlot(new Slot("A1", new Product("COKE", "Coke 330ml", Money.ofDollars(1.25)), 3, 10));
        inventory.addSlot(new Slot("A2", new Product("CHIPS", "Chips", Money.ofDollars(2.00)), 3, 10));

        CashBox cashBox = new CashBox();
        cashBox.load(Denomination.QUARTER, 20);
        cashBox.load(Denomination.DIME, 20);
        cashBox.load(Denomination.NICKEL, 20);
        cashBox.load(Denomination.ONE_DOLLAR, 10);

        VendingMachine machine = new VendingMachine(
                inventory, cashBox, new GreedyChangeMaker(), new MockCardPaymentGateway());
        AdminService admin = new AdminService(machine);

        System.out.println("=== Scenario 1: cash purchase with change ===");
        machine.insertCoin(Denomination.ONE_DOLLAR);
        machine.insertCoin(Denomination.QUARTER);
        machine.insertCoin(Denomination.QUARTER);
        System.out.println("State: " + machine.getCurrentStateName() + ", balance: " + machine.getCurrentBalance());
        machine.selectProductForCash("A1");
        System.out.println("State after purchase: " + machine.getCurrentStateName());
        System.out.println("Last transaction: " + machine.getTransactionLog().all().get(0));

        System.out.println();
        System.out.println("=== Scenario 2: insufficient funds then top-up ===");
        machine.insertCoin(Denomination.QUARTER);
        try {
            machine.selectProductForCash("A2");
        } catch (InsufficientFundsException e) {
            System.out.println("Expected rejection: " + e.getMessage());
        }
        machine.insertCoin(Denomination.ONE_DOLLAR);
        machine.insertCoin(Denomination.ONE_DOLLAR);
        machine.selectProductForCash("A2");
        System.out.println("Purchased A2, state: " + machine.getCurrentStateName());

        System.out.println();
        System.out.println("=== Scenario 3: card purchase ===");
        machine.payByCard("A1");
        System.out.println("State: " + machine.getCurrentStateName());

        System.out.println();
        System.out.println("=== Scenario 4: cancel with refund ===");
        machine.insertCoin(Denomination.QUARTER);
        machine.insertCoin(Denomination.QUARTER);
        System.out.println("Balance before cancel: " + machine.getCurrentBalance());
        machine.cancel();
        System.out.println("Balance after cancel: " + machine.getCurrentBalance()
                + ", state: " + machine.getCurrentStateName());

        System.out.println();
        System.out.println("=== Scenario 5: drain remaining stock, observe OutOfStockState, then admin restocks ===");
        machine.payByCard("A1"); // A1 now empty (started at 3, minus scenario1 and scenario3)
        machine.payByCard("A2"); // A2 2 -> 1
        machine.payByCard("A2"); // A2 1 -> 0, inventory now completely empty
        System.out.println("State after draining all stock: " + machine.getCurrentStateName());
        admin.restock("A1", 5);
        admin.restock("A2", 5);
        System.out.println("State after restock: " + machine.getCurrentStateName());

        System.out.println();
        System.out.println("All recorded transactions:");
        machine.getTransactionLog().all().forEach(System.out::println);
    }
}
