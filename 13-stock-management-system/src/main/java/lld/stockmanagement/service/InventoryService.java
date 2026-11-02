package lld.stockmanagement.service;

import lld.stockmanagement.audit.AuditTrail;
import lld.stockmanagement.exception.SkuNotFoundException;
import lld.stockmanagement.exception.WarehouseNotFoundException;
import lld.stockmanagement.ledger.InventoryLedger;
import lld.stockmanagement.model.InventoryKey;
import lld.stockmanagement.model.InventoryLevel;
import lld.stockmanagement.model.MovementType;
import lld.stockmanagement.model.Sku;
import lld.stockmanagement.model.StockMovement;
import lld.stockmanagement.model.Warehouse;
import lld.stockmanagement.observer.StockEvent;
import lld.stockmanagement.observer.StockEventListener;
import lld.stockmanagement.repository.SkuRepository;
import lld.stockmanagement.repository.WarehouseRepository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Facade (Facade pattern) over the catalog repositories, the inventory ledger
 * (Strategy pattern — see {@link InventoryLedger}), the audit trail, and the
 * observer registry. This is the single entry point application code is expected to
 * call; it exists so callers never need to know that a stock-in also has to produce
 * an audit record and fan out to listeners — that sequencing is this class's one
 * responsibility.
 *
 * <p>Every public mutation method follows the same three-step sequence:
 * <ol>
 *   <li>Validate referenced SKU/warehouse exist.</li>
 *   <li>Delegate the actual counter mutation to the injected {@link InventoryLedger}
 *       (which enforces the no-overselling invariant and returns the authoritative
 *       post-mutation {@link InventoryLevel}).</li>
 *   <li>Record a {@link StockMovement} in the audit trail and notify observers —
 *       <i>after</i> the ledger confirms success, so a rejected mutation (e.g.
 *       {@code InsufficientStockException}) never produces a phantom audit entry or
 *       a false alert.</li>
 * </ol>
 */
public final class InventoryService {

    private final SkuRepository skuRepository;
    private final WarehouseRepository warehouseRepository;
    private final InventoryLedger ledger;
    private final AuditTrail auditTrail;
    private final List<StockEventListener> listeners = new CopyOnWriteArrayList<>();

    public InventoryService(SkuRepository skuRepository, WarehouseRepository warehouseRepository,
                             InventoryLedger ledger, AuditTrail auditTrail) {
        this.skuRepository = skuRepository;
        this.warehouseRepository = warehouseRepository;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
    }

    public void addListener(StockEventListener listener) {
        listeners.add(listener);
    }

    public void registerSku(Sku sku) {
        skuRepository.register(sku);
    }

    public void registerWarehouse(Warehouse warehouse) {
        warehouseRepository.register(warehouse);
    }

    /** Onboards a (sku, warehouse) pair into the ledger with an opening balance. */
    public void initializeInventory(String skuCode, String warehouseCode, int openingQuantity) {
        InventoryKey key = resolveKey(skuCode, warehouseCode);
        ledger.initialize(key, openingQuantity);
    }

    public InventoryLevel getLevel(String skuCode, String warehouseCode) {
        return ledger.snapshot(resolveKey(skuCode, warehouseCode));
    }

    public InventoryLevel stockIn(String skuCode, String warehouseCode, int quantity, String referenceId) {
        InventoryKey key = resolveKey(skuCode, warehouseCode);
        InventoryLevel after = ledger.stockIn(key, quantity);
        recordAndNotify(key, MovementType.STOCK_IN, quantity, after, referenceId);
        return after;
    }

    /** Reserves stock for a pending order. Throws {@code InsufficientStockException} if oversold. */
    public InventoryLevel reserveForOrder(String skuCode, String warehouseCode, int quantity, String orderId) {
        InventoryKey key = resolveKey(skuCode, warehouseCode);
        InventoryLevel after = ledger.reserve(key, quantity);
        recordAndNotify(key, MovementType.RESERVE, quantity, after, orderId);
        return after;
    }

    /** Cancels a previously reserved order, returning stock to available. */
    public InventoryLevel releaseReservation(String skuCode, String warehouseCode, int quantity, String orderId) {
        InventoryKey key = resolveKey(skuCode, warehouseCode);
        InventoryLevel after = ledger.releaseReservation(key, quantity);
        recordAndNotify(key, MovementType.RELEASE_RESERVATION, quantity, after, orderId);
        return after;
    }

    /** Ships a reserved order; reserved quantity permanently leaves the warehouse. */
    public InventoryLevel fulfillOrder(String skuCode, String warehouseCode, int quantity, String orderId) {
        InventoryKey key = resolveKey(skuCode, warehouseCode);
        InventoryLevel after = ledger.fulfillReservation(key, quantity);
        recordAndNotify(key, MovementType.FULFILL_RESERVATION, quantity, after, orderId);
        return after;
    }

    /** Manual cycle-count correction. {@code delta} may be positive or negative. */
    public InventoryLevel adjustStock(String skuCode, String warehouseCode, int delta, String reason) {
        InventoryKey key = resolveKey(skuCode, warehouseCode);
        InventoryLevel after = ledger.adjust(key, delta);
        recordAndNotify(key, MovementType.ADJUSTMENT, delta, after, reason);
        return after;
    }

    public List<StockMovement> auditHistory(String skuCode, String warehouseCode) {
        return auditTrail.history(resolveKey(skuCode, warehouseCode));
    }

    private void recordAndNotify(InventoryKey key, MovementType type, int quantity,
                                  InventoryLevel after, String referenceId) {
        auditTrail.record(new StockMovement(key, type, quantity, after.getAvailable(), after.getReserved(), referenceId));
        StockEvent event = new StockEvent(key, type, after);
        for (StockEventListener listener : listeners) {
            listener.onStockEvent(event);
        }
    }

    private InventoryKey resolveKey(String skuCode, String warehouseCode) {
        if (!skuRepository.exists(skuCode)) {
            throw new SkuNotFoundException(skuCode);
        }
        if (!warehouseRepository.exists(warehouseCode)) {
            throw new WarehouseNotFoundException(warehouseCode);
        }
        return new InventoryKey(skuCode, warehouseCode);
    }
}
