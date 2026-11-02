package lld.stockmanagement.model;

import java.util.Objects;

/**
 * A physical storage location (warehouse, store backroom, or fulfillment center).
 * Immutable value object; identity is the business {@code warehouseCode}.
 */
public final class Warehouse {

    private final String warehouseCode;
    private final String name;
    private final String region;

    public Warehouse(String warehouseCode, String name, String region) {
        this.warehouseCode = requireNonBlank(warehouseCode, "warehouseCode");
        this.name = requireNonBlank(name, "name");
        this.region = requireNonBlank(region, "region");
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public String getWarehouseCode() {
        return warehouseCode;
    }

    public String getName() {
        return name;
    }

    public String getRegion() {
        return region;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Warehouse warehouse)) return false;
        return warehouseCode.equals(warehouse.warehouseCode);
    }

    @Override
    public int hashCode() {
        return warehouseCode.hashCode();
    }

    @Override
    public String toString() {
        return "Warehouse{" + warehouseCode + " '" + name + "'}";
    }
}
