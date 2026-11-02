package lld.stockmanagement;

import lld.stockmanagement.audit.InMemoryAuditTrail;
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

/**
 * Runnable demonstration of the Stock Management System: registers a catalog,
 * stocks in inventory, reserves/fulfils an order, triggers a low-stock alert and a
 * reorder suggestion, and prints the resulting audit trail.
 */
public final class Main {

    public static void main(String[] args) {
        InMemorySkuRepository skuRepository = new InMemorySkuRepository();
        InMemoryWarehouseRepository warehouseRepository = new InMemoryWarehouseRepository();
        InventoryService inventoryService = new InventoryService(
                skuRepository, warehouseRepository,
                new OptimisticVersionedInventoryLedger(),
                new InMemoryAuditTrail());

        Sku widget = new Sku("WIDGET-001", "Blue Widget", "EACH");
        Warehouse mainWarehouse = new Warehouse("WH-EAST", "East Fulfillment Center", "US-EAST");
        inventoryService.registerSku(widget);
        inventoryService.registerWarehouse(mainWarehouse);

        LowStockAlertListener lowStockAlertListener = new LowStockAlertListener(10);
        lowStockAlertListener.addSink(message -> System.out.println("[ALERT] " + message));
        inventoryService.addListener(lowStockAlertListener);

        ReorderAutomationListener reorderAutomationListener = new ReorderAutomationListener();
        reorderAutomationListener.registerPolicy(
                new InventoryKey(widget.getSkuCode(), mainWarehouse.getWarehouseCode()),
                new ReorderPolicy(10, 100));
        inventoryService.addListener(reorderAutomationListener);

        inventoryService.initializeInventory(widget.getSkuCode(), mainWarehouse.getWarehouseCode(), 50);
        System.out.println("Opening level: " + inventoryService.getLevel(widget.getSkuCode(), mainWarehouse.getWarehouseCode()));

        inventoryService.stockIn(widget.getSkuCode(), mainWarehouse.getWarehouseCode(), 20, "PO-1001");
        System.out.println("After stock-in: " + inventoryService.getLevel(widget.getSkuCode(), mainWarehouse.getWarehouseCode()));

        inventoryService.reserveForOrder(widget.getSkuCode(), mainWarehouse.getWarehouseCode(), 55, "ORDER-5001");
        System.out.println("After reserve 55: " + inventoryService.getLevel(widget.getSkuCode(), mainWarehouse.getWarehouseCode()));

        // This reservation pushes available to 10, at or below the reorder point (10) and the
        // low-stock threshold (10) -> both the alert listener and reorder listener fire.
        inventoryService.reserveForOrder(widget.getSkuCode(), mainWarehouse.getWarehouseCode(), 5, "ORDER-5002");
        InventoryLevel afterSecondReserve = inventoryService.getLevel(widget.getSkuCode(), mainWarehouse.getWarehouseCode());
        System.out.println("After reserve 5: " + afterSecondReserve);

        inventoryService.fulfillOrder(widget.getSkuCode(), mainWarehouse.getWarehouseCode(), 55, "ORDER-5001");
        System.out.println("After fulfilling ORDER-5001: "
                + inventoryService.getLevel(widget.getSkuCode(), mainWarehouse.getWarehouseCode()));

        System.out.println("\nReorder requests raised: " + reorderAutomationListener.getRaisedRequests());

        System.out.println("\nAudit trail:");
        for (StockMovement movement : inventoryService.auditHistory(widget.getSkuCode(), mainWarehouse.getWarehouseCode())) {
            System.out.println("  " + movement);
        }
    }
}
