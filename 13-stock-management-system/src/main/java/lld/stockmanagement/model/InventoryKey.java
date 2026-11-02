package lld.stockmanagement.model;

import java.util.Objects;

/**
 * Composite key identifying one inventory ledger row: a single SKU inside a single
 * warehouse. This is the unit of concurrency control — every locking strategy in
 * {@code lld.stockmanagement.ledger} partitions its data structure by this key, so
 * contention on one (sku, warehouse) pair never blocks operations on another.
 */
public final class InventoryKey {

    private final String skuCode;
    private final String warehouseCode;

    public InventoryKey(String skuCode, String warehouseCode) {
        this.skuCode = Objects.requireNonNull(skuCode, "skuCode");
        this.warehouseCode = Objects.requireNonNull(warehouseCode, "warehouseCode");
    }

    public static InventoryKey of(Sku sku, Warehouse warehouse) {
        return new InventoryKey(sku.getSkuCode(), warehouse.getWarehouseCode());
    }

    public String getSkuCode() {
        return skuCode;
    }

    public String getWarehouseCode() {
        return warehouseCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InventoryKey that)) return false;
        return skuCode.equals(that.skuCode) && warehouseCode.equals(that.warehouseCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(skuCode, warehouseCode);
    }

    @Override
    public String toString() {
        return skuCode + "@" + warehouseCode;
    }
}
