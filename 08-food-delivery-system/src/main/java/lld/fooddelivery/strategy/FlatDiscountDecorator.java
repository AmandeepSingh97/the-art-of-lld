package lld.fooddelivery.strategy;

import lld.fooddelivery.domain.Money;

/**
 * Applies a flat-amount discount (e.g. "₹50 off") gated by a minimum order value and, in the
 * common "first order" promo case, a {@code firstOrderOnly} flag read from
 * {@link PricingContext#firstOrderForCustomer()}.
 */
public final class FlatDiscountDecorator extends PriceCalculatorDecorator {

    private final Money flatAmount;
    private final Money minOrderValue;
    private final boolean firstOrderOnly;

    public FlatDiscountDecorator(PriceCalculator delegate, Money flatAmount, Money minOrderValue, boolean firstOrderOnly) {
        super(delegate);
        this.flatAmount = flatAmount;
        this.minOrderValue = minOrderValue;
        this.firstOrderOnly = firstOrderOnly;
    }

    @Override
    public Money calculate(PricingContext context) {
        Money runningTotal = delegate.calculate(context);
        boolean eligible = context.subtotal().compareTo(minOrderValue) >= 0
                && (!firstOrderOnly || context.firstOrderForCustomer());
        if (!eligible) {
            return runningTotal;
        }
        return runningTotal.subtractFloored(flatAmount);
    }
}
