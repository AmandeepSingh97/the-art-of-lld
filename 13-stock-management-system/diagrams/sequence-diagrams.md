# Sequence Diagrams

## 1. Reserve stock for an order (happy path + low-stock alert)

```mermaid
sequenceDiagram
    actor Caller
    participant Service as InventoryService
    participant SkuRepo as SkuRepository
    participant WhRepo as WarehouseRepository
    participant Ledger as InventoryLedger
    participant Audit as AuditTrail
    participant LowStock as LowStockAlertListener
    participant Reorder as ReorderAutomationListener

    Caller->>Service: reserveForOrder(sku, warehouse, qty, orderId)
    Service->>SkuRepo: exists(sku)
    SkuRepo-->>Service: true
    Service->>WhRepo: exists(warehouse)
    WhRepo-->>Service: true
    Service->>Ledger: reserve(key, qty)
    Note over Ledger: Strategy-specific atomic mutation.<br/>Throws InsufficientStockException if qty > available.
    Ledger-->>Service: InventoryLevel(available', reserved')
    Service->>Audit: record(StockMovement(RESERVE, ...))
    Service->>LowStock: onStockEvent(event)
    LowStock-->>Service: (alerts if available' <= threshold)
    Service->>Reorder: onStockEvent(event)
    Reorder-->>Service: (raises ReorderRequest if available' <= reorderPoint)
    Service-->>Caller: InventoryLevel(available', reserved')
```

## 2. Reserve stock — insufficient stock (no overselling)

```mermaid
sequenceDiagram
    actor Caller
    participant Service as InventoryService
    participant Ledger as InventoryLedger
    participant Audit as AuditTrail

    Caller->>Service: reserveForOrder(sku, warehouse, qty=1000, orderId)
    Service->>Ledger: reserve(key, 1000)
    Note over Ledger: available(50) < requested(1000)
    Ledger-->>Service: throws InsufficientStockException
    Note over Service,Audit: Exception propagates before record()/notify() run -<br/>no audit entry, no listener notification, state unchanged.
    Service-->>Caller: throws InsufficientStockException
```

## 3. Concurrent reservations on the same SKU (optimistic strategy)

```mermaid
sequenceDiagram
    participant T1 as Thread A
    participant T2 as Thread B
    participant Row as AtomicReference<InventoryLevel>

    Note over Row: initial: available=10, version=0
    T1->>Row: get() -> available=10, v=0
    T2->>Row: get() -> available=10, v=0
    T1->>T1: compute candidate: available=4, v=1 (reserve 6)
    T2->>T2: compute candidate: available=5, v=1 (reserve 5)
    T1->>Row: compareAndSet(old v=0, new v=1)
    Row-->>T1: success (available=4, v=1)
    T2->>Row: compareAndSet(old v=0, new v=1)
    Row-->>T2: FAILS (row is now v=1, not v=0)
    T2->>Row: retry: get() -> available=4, v=1
    T2->>T2: recompute: 4 < 5 requested
    T2-->>T2: throws InsufficientStockException (correctly, against fresh data)
```

## 4. Stock-in / replenishment (restocking after a low-stock alert)

```mermaid
sequenceDiagram
    actor Warehouse as Warehouse Worker
    participant Service as InventoryService
    participant Ledger as InventoryLedger
    participant Audit as AuditTrail
    participant Reorder as ReorderAutomationListener

    Warehouse->>Service: stockIn(sku, warehouse, qty, poReference)
    Service->>Ledger: stockIn(key, qty)
    Ledger-->>Service: InventoryLevel(available increased)
    Service->>Audit: record(StockMovement(STOCK_IN, ...))
    Service->>Reorder: onStockEvent(event)
    Note over Reorder: available' now above reorderPoint -><br/>requestAlreadyRaised flag cleared for this key
    Service-->>Warehouse: InventoryLevel
```
