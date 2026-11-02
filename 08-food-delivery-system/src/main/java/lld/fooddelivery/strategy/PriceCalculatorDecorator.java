package lld.fooddelivery.strategy;

import java.util.Objects;

/** Base class for all pricing decorators; holds the wrapped component each decorator adjusts. */
public abstract class PriceCalculatorDecorator implements PriceCalculator {

    protected final PriceCalculator delegate;

    protected PriceCalculatorDecorator(PriceCalculator delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }
}
