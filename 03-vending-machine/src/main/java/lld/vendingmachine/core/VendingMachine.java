package lld.vendingmachine.core;

import lld.vendingmachine.change.ChangeMaker;
import lld.vendingmachine.domain.CashBox;
import lld.vendingmachine.domain.Denomination;
import lld.vendingmachine.domain.Inventory;
import lld.vendingmachine.domain.Money;
import lld.vendingmachine.domain.Product;
import lld.vendingmachine.exceptions.InsufficientChangeException;
import lld.vendingmachine.exceptions.InsufficientFundsException;
import lld.vendingmachine.exceptions.InvalidStateTransitionException;
import lld.vendingmachine.exceptions.PaymentDeclinedException;
import lld.vendingmachine.exceptions.ProductNotFoundException;
import lld.vendingmachine.exceptions.SlotOutOfStockException;
import lld.vendingmachine.payment.CardPaymentGateway;
import lld.vendingmachine.state.IdleState;
import lld.vendingmachine.state.VendingMachineContext;
import lld.vendingmachine.state.VendingMachineState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Facade and State-pattern Context for the whole machine. This is the single
 * public entry point a UI (keypad/touchscreen controller) or a test drives;
 * it never contains purchase business logic itself — every customer action
 * is delegated to {@link #currentState}, which is swapped out as the machine
 * moves through its lifecycle (Idle -> HasMoney -> Dispensing -> Idle/OutOfStock,
 * or any state -> Maintenance).
 *
 * <p><b>Concurrency model (see README section 11 for full discussion):</b>
 * every customer/operator-facing method acquires {@link #machineLock} for its
 * whole duration. This is a deliberate <em>coarse-grained</em> lock, and it is
 * the right call here: a single physical vending machine has one keypad, one
 * coin slot, one card reader, and one display — only one logical "customer
 * session" can ever be in flight at a time, so serializing front-panel
 * operations does not sacrifice any real parallelism; it just makes explicit
 * what is already physically true. The lock is intentionally scoped more
 * narrowly than "the whole machine object": {@link Inventory} and
 * {@link CashBox} have their own finer-grained internal locks so that admin
 * operations (restock, cash collection) on a slot/denomination not involved in
 * the current customer session do not have to wait on {@code machineLock}.</p>
 */
public final class VendingMachine implements VendingMachineContext {

    private final Inventory inventory;
    private final CashBox cashBox;
    private final ChangeMaker changeMaker;
    private final CardPaymentGateway cardGateway;
    private final TransactionLog transactionLog = new TransactionLog();
    private final ReentrantLock machineLock = new ReentrantLock();

    // All fields below are mutated only while holding machineLock.
    private volatile VendingMachineState currentState = new IdleState();
    private Money balance = Money.ZERO;
    private final List<Denomination> insertedDenominations = new ArrayList<>();
    private String selectedSlotId;

    public VendingMachine(Inventory inventory, CashBox cashBox, ChangeMaker changeMaker,
                           CardPaymentGateway cardGateway) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.cashBox = Objects.requireNonNull(cashBox, "cashBox");
        this.changeMaker = Objects.requireNonNull(changeMaker, "changeMaker");
        this.cardGateway = Objects.requireNonNull(cardGateway, "cardGateway");
    }

    // ----------------------------------------------------------------
    // Customer-facing API (each call is one atomic front-panel operation)
    // ----------------------------------------------------------------

    public void insertCoin(Denomination denomination) {
        machineLock.lock();
        try {
            currentState.insertCoin(this, denomination);
        } finally {
            machineLock.unlock();
        }
    }

    public void selectProductForCash(String slotId)
            throws InsufficientFundsException, InsufficientChangeException,
            SlotOutOfStockException, ProductNotFoundException {
        machineLock.lock();
        try {
            currentState.selectProductForCash(this, slotId);
        } finally {
            machineLock.unlock();
        }
    }

    public void payByCard(String slotId)
            throws PaymentDeclinedException, SlotOutOfStockException, ProductNotFoundException {
        machineLock.lock();
        try {
            currentState.payByCard(this, slotId);
        } finally {
            machineLock.unlock();
        }
    }

    public void cancel() {
        machineLock.lock();
        try {
            currentState.cancel(this);
        } finally {
            machineLock.unlock();
        }
    }

    public void enterMaintenance() {
        machineLock.lock();
        try {
            currentState.enterMaintenance(this);
        } finally {
            machineLock.unlock();
        }
    }

    public void exitMaintenance() {
        machineLock.lock();
        try {
            currentState.exitMaintenance(this);
        } finally {
            machineLock.unlock();
        }
    }

    /** Called by admin tooling after a restock so OutOfStockState can recover to Idle. */
    public void notifyInventoryReplenished() {
        machineLock.lock();
        try {
            currentState.onInventoryReplenished(this);
        } finally {
            machineLock.unlock();
        }
    }

    public String getCurrentStateName() {
        // Deliberately lock-free: a display/UI poll of "what state are we in" is a
        // best-effort read, not a decision input for a purchase, so a momentarily
        // stale read (visible via `volatile`) is an acceptable trade-off for not
        // contending with machineLock on every UI refresh tick.
        return currentState.name();
    }

    public Money getCurrentBalance() {
        machineLock.lock();
        try {
            return balance;
        } finally {
            machineLock.unlock();
        }
    }

    public TransactionLog getTransactionLog() {
        return transactionLog;
    }

    public Inventory getInventoryView() {
        return inventory;
    }

    public CashBox getCashBoxView() {
        return cashBox;
    }

    // ----------------------------------------------------------------
    // VendingMachineContext — package-visible-in-spirit seam for state classes.
    // Every method below is only ever invoked while machineLock is already held
    // by the public method that dispatched into currentState, so no additional
    // locking is needed (and re-locking a ReentrantLock would be redundant,
    // not incorrect, since it is reentrant — but it is unnecessary here).
    // ----------------------------------------------------------------

    @Override
    public Money getBalance() {
        return balance;
    }

    @Override
    public void addToBalance(Denomination denomination) {
        balance = balance.add(Money.ofCents(denomination.valueInCents()));
        insertedDenominations.add(denomination);
    }

    @Override
    public List<Denomination> getInsertedDenominations() {
        return insertedDenominations;
    }

    @Override
    public void resetBalanceAndSelection() {
        balance = Money.ZERO;
        insertedDenominations.clear();
        selectedSlotId = null;
    }

    @Override
    public void setSelectedSlotId(String slotId) {
        this.selectedSlotId = slotId;
    }

    @Override
    public String getSelectedSlotId() {
        return selectedSlotId;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public CashBox getCashBox() {
        return cashBox;
    }

    @Override
    public ChangeMaker getChangeMaker() {
        return changeMaker;
    }

    @Override
    public CardPaymentGateway getCardGateway() {
        return cardGateway;
    }

    @Override
    public void transitionTo(VendingMachineState newState) {
        this.currentState = newState;
    }

    @Override
    public void dispenseAndLog(Product product, Map<Denomination, Integer> change, String paymentDescription) {
        // In a real machine this is where we would pulse the dispense motor and the
        // coin/bill return actuator. Here we simply record the outcome.
        Transaction txn = new Transaction(
                transactionLog.nextTransactionId(),
                product.getProductId(),
                product.getName(),
                product.getPrice(),
                paymentDescription,
                change,
                Instant.now());
        transactionLog.record(txn);
    }

    @Override
    public void refundInsertedCash() {
        // Exact-coins-back refund: we never touch the CashBox here because the
        // inserted cash was never deposited into it in the first place for a
        // mid-flight cancel. (The one case where it *was* deposited —
        // InsufficientChangeException — is reversed via CashBox.reverseDeposit
        // inside CashPaymentStrategy before this method is even reached.)
        insertedDenominations.clear();
        balance = Money.ZERO;
        selectedSlotId = null;
    }

    /** Thrown by callers that bypass state dispatch incorrectly; kept for completeness/documentation. */
    public static InvalidStateTransitionException unsupported(String op, String state) {
        return new InvalidStateTransitionException(op, state);
    }
}
