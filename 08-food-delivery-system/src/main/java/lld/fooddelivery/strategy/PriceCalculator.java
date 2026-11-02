package lld.fooddelivery.strategy;

import lld.fooddelivery.domain.Money;

/**
 * Decorator-pattern component for computing an order's final payable total from a
 * {@link PricingContext}. The base implementation ({@link BaseSubtotalCalculator}) simply
 * returns the item subtotal; each decorator wraps a delegate and layers one adjustment
 * (percentage discount, flat discount, delivery fee, ...) on top of the delegate's result.
 *
 * <p>Decorator was chosen over a {@code Strategy} here specifically because discounts and fees
 * <em>compose</em> — a real order commonly has "20% off up to ₹100" <em>and</em> "₹50 off
 * first order" <em>and</em> a delivery fee, all applied together — and Decorator lets the
 * caller build an arbitrary pipeline at runtime by nesting constructors, whereas a single
 * {@code PricingStrategy} enum/strategy would need a combinatorial explosion of
 * strategy classes (one per combination) to express the same stacking. See README "Design
 * Patterns" for the full comparison and why a single flat {@code Strategy} was rejected here.
 */
public interface PriceCalculator {
    Money calculate(PricingContext context);
}
