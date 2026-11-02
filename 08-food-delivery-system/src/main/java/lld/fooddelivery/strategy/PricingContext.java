package lld.fooddelivery.strategy;

import lld.fooddelivery.domain.Money;

/**
 * Immutable input to a {@link PriceCalculator} pipeline: the pre-discount item subtotal plus
 * whatever contextual facts a discount/fee rule needs to evaluate (first-order eligibility,
 * delivery distance for distance-based fees, etc.). Kept as a single value object rather than
 * scattering these as method parameters so new rules can read new context fields without
 * changing every decorator's method signature.
 */
public record PricingContext(Money subtotal, boolean firstOrderForCustomer, double deliveryDistanceKm) {
}
