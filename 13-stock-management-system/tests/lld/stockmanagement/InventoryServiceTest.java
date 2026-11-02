package lld.stockmanagement;

import lld.stockmanagement.audit.InMemoryAuditTrail;
import lld.stockmanagement.exception.InsufficientStockException;
import lld.stockmanagement.exception.SkuNotFoundException;
import lld.stockmanagement.ledger.InventoryLedger;
import lld.stockmanagement.ledger.OptimisticVersionedInventoryLedger;
import lld.stockmanagement.model.InventoryKey;
import lld.stockmanagement.model.InventoryLevel;
import lld.stockmanagement.model.ReorderPolicy;
import lld.stockmanagement.model.Sku;
import lld.stockmanagement.model.StockMovement;
import lld.stockmanagement.model.Warehouse;
import lld.stockmanagement.observer.LowStockAlertListener;
import lld.stockmanagement.observer.ReorderAutomationListener;
import lld.stockmanagement.repository.InMemorySkuRepository;
import lld.stockmanagement.repository.InMemoryWarehouseRepository;
import lld.stockmanagement.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for {@link InventoryService}: the facade's orchestration of
 * ledger mutation, audit recording, and observer notification.
 */
class InventoryServiceTest {

    private static final String SKU = "SKU-1";
    private static final String WAREHOUSE = "WH-1";

    private InventoryService service;

    @BeforeEach
    void setUp() {
        InMemorySkuRepository skuRepository = new InMemorySkuRepository();
        InMemoryWarehouseRepository warehouseRepository = new InMemoryWarehouseRepository();
        InventoryLedger ledger = new OptimisticVersionedInventoryLedger();
        service = new InventoryService(skuRepository, warehouseRepository, ledger, new InMemoryAuditTrail());
        service.registerSku(new Sku(SKU, "Test Widget", "EACH"));
        service.registerWarehouse(new Warehouse(WAREHOUSE, "Test Warehouse", "US-WEST"));
        service.initializeInventory(SKU, WAREHOUSE, 100);
    }

    @Test
    void stockInIncreasesAvailable() {
        InventoryLevel level = service.stockIn(SKU, WAREHOUSE, 25, "PO-1");
        assertEquals(125, level.getAvailable());
        assertEquals(0, level.getReserved());
    }

    @Test
    void reserveMovesQuantityFromAvailableToReserved() {
        InventoryLevel level = service.reserveForOrder(SKU, WAREHOUSE, 30, "ORDER-1");
        assertEquals(70, level.getAvailable());
        assertEquals(30, level.getReserved());
    }

    @Test
    void reservingMoreThanAvailableThrowsAndLeavesStateUnchanged() {
        assertThrows(InsufficientStockException.class,
                () -> service.reserveForOrder(SKU, WAREHOUSE, 1000, "ORDER-2"));
        InventoryLevel level = service.getLevel(SKU, WAREHOUSE);
        assertEquals(100, level.getAvailable(), "a rejected reservation must not change available");
        assertEquals(0, level.getReserved());
    }

    @Test
    void rejectedReservationProducesNoAuditEntry() {
        assertThrows(InsufficientStockException.class,
                () -> service.reserveForOrder(SKU, WAREHOUSE, 1000, "ORDER-2"));
        assertTrue(service.auditHistory(SKU, WAREHOUSE).isEmpty(),
                "a failed mutation must not leave an audit trace");
    }

    @Test
    void releaseReservationReturnsStockToAvailable() {
        service.reserveForOrder(SKU, WAREHOUSE, 40, "ORDER-3");
        InventoryLevel level = service.releaseReservation(SKU, WAREHOUSE, 15, "ORDER-3");
        assertEquals(75, level.getAvailable());
        assertEquals(25, level.getReserved());
    }

    @Test
    void fulfillOrderRemovesReservedQuantityPermanently() {
        service.reserveForOrder(SKU, WAREHOUSE, 40, "ORDER-4");
        InventoryLevel level = service.fulfillOrder(SKU, WAREHOUSE, 40, "ORDER-4");
        assertEquals(60, level.getAvailable());
        assertEquals(0, level.getReserved());
    }

    @Test
    void adjustCanCorrectStockDownwardButNotBelowZero() {
        assertThrows(InsufficientStockException.class, () -> service.adjustStock(SKU, WAREHOUSE, -1000, "cycle-count"));
        InventoryLevel level = service.adjustStock(SKU, WAREHOUSE, -10, "cycle-count");
        assertEquals(90, level.getAvailable());
    }

    @Test
    void unknownSkuIsRejected() {
        assertThrows(SkuNotFoundException.class, () -> service.stockIn("NOPE", WAREHOUSE, 1, "PO-X"));
    }

    @Test
    void auditTrailCapturesFullHistoryInOrder() {
        service.stockIn(SKU, WAREHOUSE, 10, "PO-5");
        service.reserveForOrder(SKU, WAREHOUSE, 5, "ORDER-5");
        service.fulfillOrder(SKU, WAREHOUSE, 5, "ORDER-5");

        List<StockMovement> history = service.auditHistory(SKU, WAREHOUSE);
        assertEquals(3, history.size());
        assertEquals("PO-5", history.get(0).getReferenceId());
        assertEquals("ORDER-5", history.get(1).getReferenceId());
        assertEquals("ORDER-5", history.get(2).getReferenceId());
    }

    @Test
    void lowStockAlertFiresWhenAvailableCrossesThreshold() {
        List<String> alerts = new CopyOnWriteArrayList<>();
        LowStockAlertListener alertListener = new LowStockAlertListener(20);
        alertListener.addSink(alerts::add);
        service.addListener(alertListener);

        service.reserveForOrder(SKU, WAREHOUSE, 85, "ORDER-6"); // available -> 15, below threshold 20

        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).contains("available=15"));
    }

    @Test
    void lowStockAlertDoesNotFireAboveThreshold() {
        List<String> alerts = new CopyOnWriteArrayList<>();
        LowStockAlertListener alertListener = new LowStockAlertListener(5);
        alertListener.addSink(alerts::add);
        service.addListener(alertListener);

        service.reserveForOrder(SKU, WAREHOUSE, 50, "ORDER-7"); // available -> 50, above threshold 5

        assertTrue(alerts.isEmpty());
    }

    @Test
    void reorderAutomationRaisesExactlyOneRequestUntilRestocked() {
        ReorderAutomationListener reorderListener = new ReorderAutomationListener();
        reorderListener.registerPolicy(new InventoryKey(SKU, WAREHOUSE), new ReorderPolicy(30, 200));
        service.addListener(reorderListener);

        service.reserveForOrder(SKU, WAREHOUSE, 75, "ORDER-8"); // available -> 25, below reorder point 30
        service.reserveForOrder(SKU, WAREHOUSE, 5, "ORDER-9");  // available -> 20, still below: must NOT duplicate

        assertEquals(1, reorderListener.getRaisedRequests().size());
        assertEquals(200, reorderListener.getRaisedRequests().get(0).getSuggestedQuantity());

        service.stockIn(SKU, WAREHOUSE, 50, "PO-REPLENISH"); // available -> 70, above reorder point: flag resets
        service.reserveForOrder(SKU, WAREHOUSE, 45, "ORDER-10"); // available -> 25, below again: new request

        assertEquals(2, reorderListener.getRaisedRequests().size());
    }
}
