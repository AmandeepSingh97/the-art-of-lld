package lld.splitwise.split;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import lld.splitwise.exception.InvalidSplitException;
import lld.splitwise.model.ExpenseShare;
import lld.splitwise.model.Money;
import lld.splitwise.split.SplitInput.PercentSplitInput;

/**
 * Splits the total by caller-supplied percentages using the
 * <a href="https://en.wikipedia.org/wiki/Largest_remainder_method">largest
 * remainder (Hamilton) apportionment method</a>, which is the standard,
 * well-understood technique for converting fractional percentage shares into
 * exact integer minor units without losing or fabricating money.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Validate percentages sum to 100 within a small epsilon (0.01) to
 *       tolerate benign floating input like {@code 33.33 + 33.33 + 33.34}.</li>
 *   <li>For each participant compute the exact fractional share in minor
 *       units using {@link BigDecimal}: {@code raw = total * pct / 100}.</li>
 *   <li>Take the floor of each {@code raw} as the participant's base share;
 *       this always undershoots the total by some small number of minor
 *       units {@code R} (0 &lt;= R &lt; n).</li>
 *   <li>Sort participants by the <i>fractional remainder</i> of their raw
 *       share, descending. Give one extra minor unit to each of the top
 *       {@code R} participants (ties broken by user id for determinism).</li>
 * </ol>
 * This guarantees the sum of shares equals the total exactly, and distributes
 * the unavoidable rounding "slack" to the participants who were rounded down
 * the most — the fairest possible allocation for a discrete currency.
 */
public final class PercentSplitStrategy implements SplitStrategy {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal EPSILON = new BigDecimal("0.01");

    @Override
    public List<ExpenseShare> computeShares(Money totalAmount, SplitInput input) {
        if (!(input instanceof PercentSplitInput percentInput)) {
            throw new InvalidSplitException("PercentSplitStrategy requires a PercentSplitInput");
        }
        Map<String, BigDecimal> percentages = percentInput.percentageByUserId();

        BigDecimal percentSum = percentages.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (percentSum.subtract(HUNDRED).abs().compareTo(EPSILON) > 0) {
            throw new InvalidSplitException(
                    "Percentages must sum to 100, but summed to " + percentSum);
        }

        record RawShare(String userId, long floorMinorUnits, BigDecimal fractionalRemainder) {
        }

        long totalMinor = totalAmount.minorUnits();
        List<RawShare> rawShares = new ArrayList<>(percentages.size());
        for (Map.Entry<String, BigDecimal> entry : percentages.entrySet()) {
            BigDecimal pct = entry.getValue();
            if (pct.signum() < 0) {
                throw new InvalidSplitException(
                        "Percentage for user %s cannot be negative: %s".formatted(entry.getKey(), pct));
            }
            BigDecimal rawMinorUnits = BigDecimal.valueOf(totalMinor).multiply(pct).divide(HUNDRED, 10, RoundingMode.HALF_UP);
            long floor = rawMinorUnits.setScale(0, RoundingMode.FLOOR).longValueExact();
            BigDecimal fractionalRemainder = rawMinorUnits.subtract(BigDecimal.valueOf(floor));
            rawShares.add(new RawShare(entry.getKey(), floor, fractionalRemainder));
        }

        long distributed = rawShares.stream().mapToLong(RawShare::floorMinorUnits).sum();
        long leftover = totalMinor - distributed;
        if (leftover < 0 || leftover >= rawShares.size()) {
            // Defensive: should be mathematically impossible given floor semantics, but guard anyway.
            throw new InvalidSplitException("Unable to reconcile percentage split remainder: leftover=" + leftover);
        }

        List<RawShare> byRemainderDesc = new ArrayList<>(rawShares);
        byRemainderDesc.sort(
                Comparator.<RawShare, BigDecimal>comparing(RawShare::fractionalRemainder).reversed()
                        .thenComparing(RawShare::userId));

        java.util.Set<String> bonusRecipients = new java.util.HashSet<>();
        for (int i = 0; i < leftover; i++) {
            bonusRecipients.add(byRemainderDesc.get(i).userId());
        }

        List<ExpenseShare> shares = new ArrayList<>(rawShares.size());
        for (RawShare rawShare : rawShares) {
            long amount = rawShare.floorMinorUnits() + (bonusRecipients.contains(rawShare.userId()) ? 1 : 0);
            shares.add(new ExpenseShare(rawShare.userId(), Money.ofMinorUnits(amount)));
        }
        return shares;
    }
}
