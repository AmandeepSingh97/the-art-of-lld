package lld.digitalwallet.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable value object representing a monetary amount as an integer number
 * of "minor units" (e.g. cents for USD/INR paise) of a given {@link Currency}.
 *
 * <h2>Why integer minor units and not {@code double}/{@code float}?</h2>
 * Binary floating point cannot exactly represent most decimal fractions
 * (0.1 + 0.2 != 0.3 in IEEE-754). Doing repeated credit/debit arithmetic on
 * {@code double} balances accumulates rounding error that silently corrupts
 * money - unacceptable for a ledger that must balance to the paisa/cent.
 * {@code long} minor units give us:
 * <ul>
 *   <li>Exact arithmetic (addition/subtraction never loses precision).</li>
 *   <li>A natural mapping to how currencies are actually minted (smallest
 *       circulating unit), avoiding "what is 1/3 of a cent" problems.</li>
 *   <li>Cheap, allocation-free comparisons and hashing versus
 *       {@link BigDecimal}, while still being exact (unlike {@code double}).</li>
 * </ul>
 * {@link BigDecimal} is a defensible alternative (also exact) but is heavier
 * (object allocation per op, scale/rounding-mode bookkeeping) and most
 * real-world ledgers (Stripe, many banking cores) settle on integer minor
 * units for exactly this reason. We only touch {@link BigDecimal} at the
 * edges (parsing user input / formatting for display).
 */
public final class Money implements Comparable<Money> {

    private final long minorUnits;
    private final Currency currency;

    private Money(long minorUnits, Currency currency) {
        this.minorUnits = minorUnits;
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    public static Money ofMinorUnits(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    /**
     * Convenience factory for human-entered major-unit amounts (e.g. "19.99").
     * Converts to minor units using the currency's default fraction digits,
     * HALF_UP at the boundary only - all subsequent arithmetic stays integral.
     */
    public static Money ofMajorUnits(BigDecimal majorUnits, Currency currency) {
        int fractionDigits = currency.getDefaultFractionDigits();
        BigDecimal scaled = majorUnits.setScale(fractionDigits, RoundingMode.HALF_UP)
                .movePointRight(fractionDigits);
        return new Money(scaled.longValueExact(), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    public long minorUnits() {
        return minorUnits;
    }

    public Currency currency() {
        return currency;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(this.minorUnits, other.minorUnits), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(this.minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + this.currency + " vs " + other.currency);
        }
    }

    public BigDecimal toMajorUnits() {
        int fractionDigits = currency.getDefaultFractionDigits();
        return BigDecimal.valueOf(minorUnits).movePointLeft(fractionDigits);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(this.minorUnits, other.minorUnits);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return minorUnits == money.minorUnits && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(minorUnits, currency);
    }

    @Override
    public String toString() {
        return toMajorUnits().toPlainString() + " " + currency.getCurrencyCode();
    }
}
