package lld.fooddelivery;

import lld.fooddelivery.domain.Money;
import lld.fooddelivery.strategy.BaseSubtotalCalculator;
import lld.fooddelivery.strategy.DeliveryFeeDecorator;
import lld.fooddelivery.strategy.FlatDiscountDecorator;
import lld.fooddelivery.strategy.PercentageDiscountDecorator;
import lld.fooddelivery.strategy.PriceCalculator;
import lld.fooddelivery.strategy.PricingContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Validates the Decorator-based pricing pipeline: individual decorators and their composition.
 */
class PriceCalculatorDecoratorTest {

    @Test
    void baseCalculatorReturnsRawSubtotal() {
        PriceCalculator calculator = new BaseSubtotalCalculator();
        PricingContext context = new PricingContext(Money.of("500.00"), false, 0);
        assertEquals(Money.of("500.00"), calculator.calculate(context));
    }

    @Test
    void percentageDiscountIsCappedAtMaxDiscount() {
        // 20% of 1000 would be 200, but capped at 100.
        PriceCalculator calculator = new PercentageDiscountDecorator(new BaseSubtotalCalculator(), 20, Money.of("100.00"));
        PricingContext context = new PricingContext(Money.of("1000.00"), false, 0);
        assertEquals(Money.of("900.00"), calculator.calculate(context));
    }

    @Test
    void percentageDiscountBelowCapIsAppliedInFull() {
        PriceCalculator calculator = new PercentageDiscountDecorator(new BaseSubtotalCalculator(), 10, Money.of("100.00"));
        PricingContext context = new PricingContext(Money.of("500.00"), false, 0);
        assertEquals(Money.of("450.00"), calculator.calculate(context));
    }

    @Test
    void flatDiscountRequiresFirstOrderWhenConfigured() {
        PriceCalculator calculator = new FlatDiscountDecorator(
                new BaseSubtotalCalculator(), Money.of("50.00"), Money.of("200.00"), true);

        PricingContext firstOrder = new PricingContext(Money.of("500.00"), true, 0);
        assertEquals(Money.of("450.00"), calculator.calculate(firstOrder));

        PricingContext repeatOrder = new PricingContext(Money.of("500.00"), false, 0);
        assertEquals(Money.of("500.00"), calculator.calculate(repeatOrder));
    }

    @Test
    void flatDiscountDoesNotApplyBelowMinimumOrderValue() {
        PriceCalculator calculator = new FlatDiscountDecorator(
                new BaseSubtotalCalculator(), Money.of("50.00"), Money.of("200.00"), false);
        PricingContext tooSmall = new PricingContext(Money.of("150.00"), false, 0);
        assertEquals(Money.of("150.00"), calculator.calculate(tooSmall));
    }

    @Test
    void deliveryFeeScalesWithDistanceAndStacksOnTopOfDiscounts() {
        PriceCalculator calculator = new DeliveryFeeDecorator(
                new PercentageDiscountDecorator(new BaseSubtotalCalculator(), 10, Money.of("1000.00")),
                Money.of("20.00"), Money.of("5.00"));
        // subtotal 500, 10% off -> 450, + base fee 20 + 5*ceil(3.1)=5*4=20 -> 450+40=490
        PricingContext context = new PricingContext(Money.of("500.00"), false, 3.1);
        assertEquals(Money.of("490.00"), calculator.calculate(context));
    }

    @Test
    void discountsNeverDriveTotalNegative() {
        PriceCalculator calculator = new FlatDiscountDecorator(
                new PercentageDiscountDecorator(new BaseSubtotalCalculator(), 100, Money.of("10000.00")),
                Money.of("500.00"), Money.of("0.00"), false);
        PricingContext context = new PricingContext(Money.of("10.00"), false, 0);
        assertEquals(Money.ZERO, calculator.calculate(context));
    }

    @Test
    void stackingThreeDecoratorsAppliesThemInWrapOrder() {
        PriceCalculator calculator = new DeliveryFeeDecorator(
                new FlatDiscountDecorator(
                        new PercentageDiscountDecorator(new BaseSubtotalCalculator(), 15, Money.of("100.00")),
                        Money.of("50.00"), Money.of("200.00"), true),
                Money.of("30.00"), Money.of("0.00"));
        // subtotal 1000: 15% off capped at 100 -> -100 => 900; flat 50 off (first order, >=200) => 850; + fee 30
        PricingContext context = new PricingContext(Money.of("1000.00"), true, 2.0);
        assertEquals(Money.of("880.00"), calculator.calculate(context));
    }
}
