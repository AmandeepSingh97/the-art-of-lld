package lld.stockmanagement.ledger;

/** Runs the shared no-overselling concurrency proof against {@link PessimisticLockInventoryLedger}. */
class PessimisticLockInventoryLedgerConcurrencyTest extends AbstractInventoryLedgerConcurrencyTest {

    @Override
    protected InventoryLedger createLedger() {
        return new PessimisticLockInventoryLedger();
    }
}
