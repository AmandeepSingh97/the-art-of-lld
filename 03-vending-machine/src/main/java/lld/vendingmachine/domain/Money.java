package lld.vendingmachine.domain;

/**
 * Immutable value object representing a monetary amount in the smallest
 * currency unit (cents for USD). Using an integral minor-unit representation
 * avoids the classic floating point rounding errors that plague {@code double}
 * based money arithmetic.
 *
 * <p>This is a value object: equality is structural (by {@code cents}), there
 * are no setters, and every "mutation" returns a new instance.</p>
 */
public record Money(long cents) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("Money cannot be negative: " + cents);
        }
    }

    public static Money ofCents(long cents) {
        return new Money(cents);
    }

    public static Money ofDollars(double dollars) {
        // Round to avoid binary floating point artifacts (e.g. 1.1 -> 109.999...).
        return new Money(Math.round(dollars * 100));
    }

    public Money add(Money other) {
        return new Money(this.cents + other.cents);
    }

    /**
     * Subtracts {@code other} from this amount.
     *
     * @throws IllegalArgumentException if the result would be negative, since a
     *                                   negative balance is never a valid domain state.
     */
    public Money subtract(Money other) {
        long result = this.cents - other.cents;
        if (result < 0) {
            throw new IllegalArgumentException(
                    "Subtraction would produce a negative amount: " + this + " - " + other);
        }
        return new Money(result);
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        return this.cents >= other.cents;
    }

    public boolean isZero() {
        return this.cents == 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.cents, other.cents);
    }

    @Override
    public String toString() {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }
}
