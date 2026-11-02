package lld.bookmyshow.strategy;

import lld.bookmyshow.enums.SeatCategory;
import lld.bookmyshow.model.Show;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Strategy interface for computing the price of a single seat of a given
 * category on a given show, at a given point in time. Kept as a one-method
 * functional interface so pricing rules can be swapped per-deployment (e.g.
 * launch with {@link FixedPricingStrategy}, enable
 * {@link DynamicPricingStrategy} later) without touching
 * {@code BookingService} or any other caller.
 */
@FunctionalInterface
public interface PricingStrategy {
    BigDecimal priceFor(Show show, SeatCategory category, Instant now);
}
