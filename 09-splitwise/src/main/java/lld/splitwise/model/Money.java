package lld.splitwise.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable value object representing a monetary amount in a single currency.
 *
 * <h2>Why integer minor units instead of {@code double} or raw {@code BigDecimal}?</h2>
 * <ul>
 *   <li>{@code double}/{@code float} use binary floating point and cannot represent
 *       most decimal fractions (e.g. 0.1) exactly. Repeated addition/subtraction of
 *       money accumulates rounding error, which is unacceptable when the system's
 *       core invariant is "the sum of all shares of an expense must exactly equal
 *       the expense total" and "the sum of all net balances across all users must
 *       always equal zero".</li>
 *   <li>{@code BigDecimal} is exact but is a heavier object, has subtle scale/rounding
 *       pitfalls (e.g. {@code equals} is scale-sensitive: {@code 1.0} != {@code 1.00}),
 *       and every arithmetic operation requires an explicit {@link RoundingMode}
 *       decision. It is the right tool when a system must support arbitrary-precision
 *       or multiple currencies with different decimal scales (e.g. JPY has 0 minor
 *       units, BHD has 3) at once.</li>
 *   <li>This design assumes a <b>single currency</b> per deployment/tenant (a very
 *       common, explicitly-stated simplifying assumption for an LLD interview — see
 *       README "Clarifying Questions"). Under that assumption, representing money as
 *       a {@code long} count of "minor units" (cents/paise) gives exact integer
 *       arithmetic, is immutable, is cheap to compare/hash, and trivially supports
 *       the "distribute the remainder" logic required by equal/percentage splits.</li>
 *   <li>{@link BigDecimal} is still used internally for percentage math (which is
 *       inherently fractional), but every result is rounded back down to a
 *       {@code long} of minor units before becoming a {@link Money}, and the
 *       low-order remainder is redistributed deterministically so totals always
 *       reconcile exactly. See {@link lld.splitwise.split.PercentSplitStrategy}.</li>
 * </ul>
 *
 * <p>Thread-safety: this class is immutable (all fields {@code final}, no setters),
 * so instances are trivially safe to share across threads without synchronization.
 */
public final class Money implements Comparable<Money> {

    /** Number of minor units per major unit for the supported currency (e.g. 100 cents = 1 dollar). */
    public static final int MINOR_UNITS_SCALE = 100;

    public static final Money ZERO = new Money(0L);

    private final long minorUnits;

    private Money(long minorUnits) {
        this.minorUnits = minorUnits;
    }

    public static Money ofMinorUnits(long minorUnits) {
        return new Money(minorUnits);
    }

    /** Creates a {@link Money} from a major-unit decimal amount (e.g. {@code "12.50"} dollars). */
    public static Money ofMajorUnits(BigDecimal majorUnits) {
        Objects.requireNonNull(majorUnits, "majorUnits");
        BigDecimal minor = majorUnits.multiply(BigDecimal.valueOf(MINOR_UNITS_SCALE));
        return new Money(minor.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    public static Money ofMajorUnits(String majorUnits) {
        return ofMajorUnits(new BigDecimal(majorUnits));
    }

    public long minorUnits() {
        return minorUnits;
    }

    public Money add(Money other) {
        return new Money(Math.addExact(this.minorUnits, other.minorUnits));
    }

    public Money subtract(Money other) {
        return new Money(Math.subtractExact(this.minorUnits, other.minorUnits));
    }

    public Money negate() {
        return new Money(Math.negateExact(this.minorUnits));
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    public Money abs() {
        return minorUnits < 0 ? negate() : this;
    }

    public BigDecimal toMajorUnits() {
        return BigDecimal.valueOf(minorUnits)
                .divide(BigDecimal.valueOf(MINOR_UNITS_SCALE), 2, RoundingMode.UNNECESSARY);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.minorUnits, other.minorUnits);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return minorUnits == money.minorUnits;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(minorUnits);
    }

    @Override
    public String toString() {
        return toMajorUnits().toPlainString();
    }
}
