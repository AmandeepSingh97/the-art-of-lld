package lld.bookmyshow.enums;

import java.math.BigDecimal;

/**
 * Physical seat tiers inside a screen. Each category carries a price multiplier
 * that is applied on top of a show's base price by the pricing strategy.
 *
 * <p>Kept as an enum (not a free-form string or DB-driven lookup) because the
 * set of categories is small, stable, and known at compile time for an LLD
 * exercise. A production system would likely externalize this into a
 * configuration table — see {@code STAFF_LEVEL_DEEP_DIVE.md}.
 */
public enum SeatCategory {
    SILVER(BigDecimal.valueOf(1.0)),
    GOLD(BigDecimal.valueOf(1.5)),
    PLATINUM(BigDecimal.valueOf(2.25));

    private final BigDecimal priceMultiplier;

    SeatCategory(BigDecimal priceMultiplier) {
        this.priceMultiplier = priceMultiplier;
    }

    public BigDecimal priceMultiplier() {
        return priceMultiplier;
    }
}
