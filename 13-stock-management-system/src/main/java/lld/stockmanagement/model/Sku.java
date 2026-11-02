package lld.stockmanagement.model;

import java.util.Objects;

/**
 * A Stock Keeping Unit (SKU) — the catalog identity of a distinct, sellable/storable
 * product variant (e.g. "RED-TSHIRT-M" vs "RED-TSHIRT-L" are different SKUs).
 *
 * <p>This is an immutable value object. Its identity for equality purposes is the
 * business {@code skuCode}, not object reference — two {@code Sku} instances created
 * from two different reads of the same catalog row must compare equal and hash
 * identically so they can be used safely as map keys.
 */
public final class Sku {

    private final String skuCode;
    private final String name;
    private final String unitOfMeasure;

    public Sku(String skuCode, String name, String unitOfMeasure) {
        this.skuCode = requireNonBlank(skuCode, "skuCode");
        this.name = requireNonBlank(name, "name");
        this.unitOfMeasure = requireNonBlank(unitOfMeasure, "unitOfMeasure");
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public String getName() {
        return name;
    }

    public String getUnitOfMeasure() {
        return unitOfMeasure;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Sku sku)) return false;
        return skuCode.equals(sku.skuCode);
    }

    @Override
    public int hashCode() {
        return skuCode.hashCode();
    }

    @Override
    public String toString() {
        return "Sku{" + skuCode + " '" + name + "'}";
    }
}
