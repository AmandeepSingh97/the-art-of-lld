package lld.vendingmachine.core;

import lld.vendingmachine.admin.AdminService;
import lld.vendingmachine.change.GreedyChangeMaker;
import lld.vendingmachine.domain.CashBox;
import lld.vendingmachine.domain.Denomination;
import lld.vendingmachine.domain.Inventory;
import lld.vendingmachine.domain.Money;
import lld.vendingmachine.domain.Product;
import lld.vendingmachine.domain.Slot;
import lld.vendingmachine.exceptions.InsufficientChangeException;
import lld.vendingmachine.exceptions.InsufficientFundsException;
import lld.vendingmachine.exceptions.InvalidStateTransitionException;
import lld.vendingmachine.exceptions.PaymentDeclinedException;
import lld.vendingmachine.exceptions.SlotOutOfStockException;
import lld.vendingmachine.payment.MockCardPaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VendingMachineTest {

    private Inventory inventory;
    private CashBox cashBox;
    private VendingMachine machine;

    @BeforeEach
    void setUp() {
        inventory = new Inventory();
        inventory.addSlot(new Slot("A1", new Product("COKE", "Coke", Money.ofDollars(1.25)), 2, 10));
        inventory.addSlot(new Slot("A2", new Product("CHIPS", "Chips", Money.ofDollars(2.00)), 1, 10));

        cashBox = new CashBox();
        cashBox.load(Denomination.QUARTER, 10);
        cashBox.load(Denomination.ONE_DOLLAR, 10);

        machine = new VendingMachine(inventory, cashBox, new GreedyChangeMaker(), new MockCardPaymentGateway());
    }

    @Test
    void cashPurchaseDispensesProductAndExactChange()
            throws InsufficientFundsException, InsufficientChangeException, SlotOutOfStockException {
        machine.insertCoin(Denomination.ONE_DOLLAR);
        machine.insertCoin(Denomination.QUARTER);
        machine.insertCoin(Denomination.QUARTER);
        assertEquals("HAS_MONEY", machine.getCurrentStateName());

        machine.selectProductForCash("A1"); // price 1.25, inserted 1.50 -> 0.25 change

        assertEquals("IDLE", machine.getCurrentStateName());
        assertEquals(Money.ZERO, machine.getCurrentBalance());
        assertEquals(1, inventory.getSlot("A1").getQuantity());
        Transaction txn = machine.getTransactionLog().all().get(0);
        assertEquals(1, txn.changeDispensed().get(Denomination.QUARTER));
    }

    @Test
    void insufficientFundsKeepsMachineInHasMoneyStateAndPreservesBalance() {
        machine.insertCoin(Denomination.QUARTER);
        InsufficientFundsException ex = assertThrows(InsufficientFundsException.class,
                () -> machine.selectProductForCash("A1"));
        assertEquals(Money.ofCents(100), ex.getShortfall());
        assertEquals("HAS_MONEY", machine.getCurrentStateName());
        assertEquals(Money.ofCents(25), machine.getCurrentBalance());
        assertEquals(2, inventory.getSlot("A1").getQuantity()); // nothing dispensed
    }

    @Test
    void insufficientChangeAbortsAndRefundsExactInsertedCash() {
        // Drain the cash box of everything except dollar bills, so no change can be made for 75 cents.
        Inventory emptyChangeInventory = new Inventory();
        emptyChangeInventory.addSlot(new Slot("A1", new Product("COKE", "Coke", Money.ofDollars(1.25)), 2, 10));
        CashBox noSmallChangeBox = new CashBox();
        noSmallChangeBox.load(Denomination.ONE_DOLLAR, 10); // only $1 bills, cannot make 75 cents change

        VendingMachine strictMachine = new VendingMachine(
                emptyChangeInventory, noSmallChangeBox, new GreedyChangeMaker(), new MockCardPaymentGateway());

        strictMachine.insertCoin(Denomination.ONE_DOLLAR);
        strictMachine.insertCoin(Denomination.ONE_DOLLAR); // balance = 2.00, price = 1.25, change owed = 0.75

        assertThrows(InsufficientChangeException.class, () -> strictMachine.selectProductForCash("A1"));

        assertEquals("IDLE", strictMachine.getCurrentStateName());
        assertEquals(Money.ZERO, strictMachine.getCurrentBalance());
        assertEquals(2, emptyChangeInventory.getSlot("A1").getQuantity()); // nothing was dispensed
        // The box must not have kept the customer's money either.
        assertEquals(Money.ofCents(1000), noSmallChangeBox.totalValue());
    }

    @Test
    void cardPurchaseRequiresNoPriorCashAndNeedsNoChange()
            throws PaymentDeclinedException, SlotOutOfStockException {
        machine.payByCard("A2");
        assertEquals("IDLE", machine.getCurrentStateName());
        assertEquals(0, inventory.getSlot("A2").getQuantity());
        assertTrue(machine.getTransactionLog().all().get(0).changeDispensed().isEmpty());
    }

    @Test
    void declinedCardPaymentLeavesInventoryAndStateUnchanged() {
        VendingMachine declining = new VendingMachine(
                inventory, cashBox, new GreedyChangeMaker(), new MockCardPaymentGateway(true));
        assertThrows(PaymentDeclinedException.class, () -> declining.payByCard("A2"));
        assertEquals("IDLE", declining.getCurrentStateName());
        assertEquals(1, inventory.getSlot("A2").getQuantity());
    }

    @Test
    void cancelRefundsFullBalanceAndReturnsToIdle() {
        machine.insertCoin(Denomination.QUARTER);
        machine.insertCoin(Denomination.ONE_DOLLAR);
        machine.cancel();
        assertEquals("IDLE", machine.getCurrentStateName());
        assertEquals(Money.ZERO, machine.getCurrentBalance());
    }

    @Test
    void selectingASlotWithNoStockStaysInHasMoneyStateForAnotherAttempt() {
        inventory.getSlot("A2").dispenseOne(); // manually drain A2 to zero
        machine.insertCoin(Denomination.ONE_DOLLAR);
        machine.insertCoin(Denomination.ONE_DOLLAR);
        assertThrows(SlotOutOfStockException.class, () -> machine.selectProductForCash("A2"));
        assertEquals("HAS_MONEY", machine.getCurrentStateName());
        assertEquals(Money.ofCents(200), machine.getCurrentBalance());
    }

    @Test
    void machineEntersOutOfStockWhenLastUnitIsSold()
            throws PaymentDeclinedException, SlotOutOfStockException {
        inventory.getSlot("A1").dispenseOne(); // A1: 2 -> 1
        machine.payByCard("A1"); // A1: 1 -> 0
        machine.payByCard("A2"); // A2: 1 -> 0, now completely empty
        assertEquals("OUT_OF_STOCK", machine.getCurrentStateName());
        assertThrows(InvalidStateTransitionException.class, () -> machine.insertCoin(Denomination.QUARTER));
    }

    @Test
    void adminRestockRecoversMachineFromOutOfStockToIdle() throws PaymentDeclinedException, SlotOutOfStockException {
        inventory.getSlot("A1").dispenseOne();
        machine.payByCard("A1");
        machine.payByCard("A2");
        assertEquals("OUT_OF_STOCK", machine.getCurrentStateName());

        new AdminService(machine).restock("A2", 3);
        assertEquals("IDLE", machine.getCurrentStateName());
    }

    @Test
    void maintenanceModeBlocksCustomerOperationsAndOnlyExitsExplicitly() {
        machine.enterMaintenance();
        assertEquals("MAINTENANCE", machine.getCurrentStateName());
        assertThrows(InvalidStateTransitionException.class, () -> machine.insertCoin(Denomination.QUARTER));
        machine.exitMaintenance();
        assertEquals("IDLE", machine.getCurrentStateName());
    }

    @Test
    void cannotEnterMaintenanceMidTransaction() {
        machine.insertCoin(Denomination.QUARTER);
        assertThrows(InvalidStateTransitionException.class, machine::enterMaintenance);
    }

    /**
     * Concurrency correctness test for the front-panel lock: many threads
     * concurrently try to buy the single remaining unit of A2 via card. Because
     * every customer-facing operation is serialized by {@code machineLock},
     * exactly one must succeed and all others must observe
     * {@code SlotOutOfStockException} — never a negative quantity and never two
     * successful dispenses of the same last unit.
     */
    @Test
    void concurrentCardPurchaseAttemptsOnLastUnitDispenseExactlyOnce() throws InterruptedException {
        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger outOfStockCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    machine.payByCard("A2");
                    successCount.incrementAndGet();
                } catch (SlotOutOfStockException e) {
                    outOfStockCount.incrementAndGet();
                } catch (PaymentDeclinedException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, successCount.get());
        assertEquals(threadCount - 1, outOfStockCount.get());
        assertEquals(0, inventory.getSlot("A2").getQuantity());
        assertEquals(1, machine.getTransactionLog().all().size());
    }
}
