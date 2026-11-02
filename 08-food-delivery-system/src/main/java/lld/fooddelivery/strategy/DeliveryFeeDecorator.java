package lld.fooddelivery.strategy;

import lld.fooddelivery.domain.Money;

/**
 * Adds a distance-based delivery fee: a flat base fee plus a per-kilometre rate. Unlike the
 * discount decorators this is an additive <em>fee</em>, not a discount — included to show that
 * Decorator composes heterogeneous adjustments (discounts and fees alike), not just one kind.
 */
public final class DeliveryFeeDecorator extends PriceCalculatorDecorator {

    private final Money baseFee;
    private final Money perKmRate;

    public DeliveryFeeDecorator(PriceCalculator delegate, Money baseFee, Money perKmRate) {
        super(delegate);
        this.baseFee = baseFee;
        this.perKmRate = perKmRate;
    }

    @Override
    public Money calculate(PricingContext context) {
        Money runningTotal = delegate.calculate(context);
        Money fee = baseFee.add(perKmRate.multiply((int) Math.ceil(context.deliveryDistanceKm())));
        return runningTotal.add(fee);
    }
}
