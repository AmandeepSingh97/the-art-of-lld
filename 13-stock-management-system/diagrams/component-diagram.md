# Component Diagram

High-level module boundaries and dependency direction. Arrows point from a
dependent module to the thing it depends on. `InventoryService` is the only class
application code (`Main`, or a future REST layer) needs to know about; everything
else is reached indirectly through interfaces it holds.

```mermaid
graph TD
    Client["Client code (Main / future REST layer)"]

    subgraph Service["service"]
        InventoryService["InventoryService (Facade)"]
    end

    subgraph Ledger["ledger (Strategy)"]
        InventoryLedgerIfc["«interface» InventoryLedger"]
        Optimistic["OptimisticVersionedInventoryLedger"]
        Pessimistic["PessimisticLockInventoryLedger"]
        AtomicCas["AtomicCasInventoryLedger"]
    end

    subgraph Observer["observer (Observer)"]
        StockEventListenerIfc["«interface» StockEventListener"]
        LowStock["LowStockAlertListener"]
        Reorder["ReorderAutomationListener"]
    end

    subgraph Repository["repository (Repository)"]
        SkuRepoIfc["«interface» SkuRepository"]
        WarehouseRepoIfc["«interface» WarehouseRepository"]
        InMemSkuRepo["InMemorySkuRepository"]
        InMemWarehouseRepo["InMemoryWarehouseRepository"]
    end

    subgraph Audit["audit"]
        AuditTrailIfc["«interface» AuditTrail"]
        InMemAudit["InMemoryAuditTrail"]
    end

    subgraph Lot["lot (optional extension)"]
        LotManager["LotManager"]
        LotAllocationIfc["«interface» LotAllocationStrategy"]
        Fefo["FefoLotAllocationStrategy"]
    end

    subgraph Model["model (value objects)"]
        Sku["Sku"]
        Warehouse["Warehouse"]
        InventoryKey["InventoryKey"]
        InventoryLevel["InventoryLevel"]
        StockMovement["StockMovement"]
    end

    Client --> InventoryService
    InventoryService --> InventoryLedgerIfc
    InventoryService --> SkuRepoIfc
    InventoryService --> WarehouseRepoIfc
    InventoryService --> AuditTrailIfc
    InventoryService --> StockEventListenerIfc

    Optimistic -.implements.-> InventoryLedgerIfc
    Pessimistic -.implements.-> InventoryLedgerIfc
    AtomicCas -.implements.-> InventoryLedgerIfc

    LowStock -.implements.-> StockEventListenerIfc
    Reorder -.implements.-> StockEventListenerIfc

    InMemSkuRepo -.implements.-> SkuRepoIfc
    InMemWarehouseRepo -.implements.-> WarehouseRepoIfc
    InMemAudit -.implements.-> AuditTrailIfc

    LotManager --> LotAllocationIfc
    Fefo -.implements.-> LotAllocationIfc
    LotManager -.->|"independent extension, not called by InventoryService"| Client

    InventoryLedgerIfc --> InventoryKey
    InventoryLedgerIfc --> InventoryLevel
    AuditTrailIfc --> StockMovement
    SkuRepoIfc --> Sku
    WarehouseRepoIfc --> Warehouse
```
