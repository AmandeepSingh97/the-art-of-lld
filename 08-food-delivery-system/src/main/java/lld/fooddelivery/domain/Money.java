package lld.fooddelivery.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable value object representing a monetary amount.
 *
 * <p>Using {@link BigDecimal} rather than {@code double}/{@code float} avoids binary
 * floating-point rounding errors that are unacceptable for money (e.g. 0.1 + 0.2 != 0.3).
 * All arithmetic is scaled to 2 decimal places with {@link RoundingMode#HALF_UP}, which is
 * the conventional rounding mode for currency in most retail/financial contexts.
 *
 * <p>Value objects are immutable and compared by value ({@code equals}/{@code hashCode}),
 * never by identity, which is why {@code Money} is a candidate for a Java {@code record}-like
 * design even though it is written as a plain final class here for clarity of invariants
 * (non-negative amounts are enforced for most call sites via factory helpers).
 */
public final class Money implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        return new Money(amount);
    }

    public static Money of(double amount) {
        return new Money(BigDecimal.valueOf(amount));
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    public BigDecimal amount() {
        return amount;
    }

    public Money add(Money other) {
        return new Money(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        return new Money(this.amount.subtract(other.amount));
    }

    /** Subtracts but never returns a negative amount; floors at {@link #ZERO}. */
    public Money subtractFloored(Money other) {
        BigDecimal result = this.amount.subtract(other.amount);
        return result.signum() < 0 ? ZERO : new Money(result);
    }

    public Money multiply(int quantity) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(quantity)));
    }

    public Money percentage(double percent) {
        BigDecimal factor = BigDecimal.valueOf(percent).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        return new Money(this.amount.multiply(factor));
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    @Override
    public int compareTo(Money o) {
        return this.amount.compareTo(o.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money)) return false;
        Money money = (Money) o;
        return amount.compareTo(money.amount) == 0;
    }

    @Override
    public int hashCode() {
        return amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return "₹" + amount.toPlainString();
    }
}
