package lld.fooddelivery.strategy;

import lld.fooddelivery.domain.Money;

/** The innermost component of a pricing pipeline: just the raw item subtotal, no adjustments. */
public final class BaseSubtotalCalculator implements PriceCalculator {

    @Override
    public Money calculate(PricingContext context) {
        return context.subtotal();
    }
}
