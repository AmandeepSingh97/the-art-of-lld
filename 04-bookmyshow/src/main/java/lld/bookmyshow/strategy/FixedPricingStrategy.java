package lld.bookmyshow.strategy;

import lld.bookmyshow.enums.SeatCategory;
import lld.bookmyshow.model.Show;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Simplest possible strategy: seat price is the show's configured base
 * price for that category, multiplied by the category's multiplier. No
 * surge, no demand sensitivity. This is the right default for an interview
 * answer and for most small/medium deployments — see
 * {@code README.md §16 Trade-offs} for when {@link DynamicPricingStrategy}
 * is worth the added complexity.
 */
public class FixedPricingStrategy implements PricingStrategy {
    @Override
    public BigDecimal priceFor(Show show, SeatCategory category, Instant now) {
        return show.basePriceFor(category).multiply(category.priceMultiplier());
    }
}
