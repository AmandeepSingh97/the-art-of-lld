package lld.fooddelivery.strategy;

import lld.fooddelivery.domain.Money;

/**
 * Applies a percentage discount on the item subtotal, capped at {@code maxDiscount} (e.g.
 * "20% off, up to ₹100") — the cap is computed against the raw subtotal, not the
 * running total, so stacking multiple percentage decorators does not compound in surprising
 * ways (each discount is computed off the same base, matching how coupon stacking is usually
 * advertised to customers: "20% off" means 20% of the bill, not 20% of an already-discounted
 * amount).
 */
public final class PercentageDiscountDecorator extends PriceCalculatorDecorator {

    private final double percent;
    private final Money maxDiscount;

    public PercentageDiscountDecorator(PriceCalculator delegate, double percent, Money maxDiscount) {
        super(delegate);
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent out of range: " + percent);
        }
        this.percent = percent;
        this.maxDiscount = maxDiscount;
    }

    @Override
    public Money calculate(PricingContext context) {
        Money runningTotal = delegate.calculate(context);
        Money discount = context.subtotal().percentage(percent);
        if (discount.compareTo(maxDiscount) > 0) {
            discount = maxDiscount;
        }
        return runningTotal.subtractFloored(discount);
    }
}
