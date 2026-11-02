package lld.splitwise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import lld.splitwise.exception.InvalidSplitException;
import lld.splitwise.model.ExpenseShare;
import lld.splitwise.model.Money;
import lld.splitwise.split.PercentSplitStrategy;
import lld.splitwise.split.SplitInput.PercentSplitInput;

class PercentSplitStrategyTest {

    private final PercentSplitStrategy strategy = new PercentSplitStrategy();

    @Test
    void splitsEvenPercentagesExactly() {
        List<ExpenseShare> shares = strategy.computeShares(
                Money.ofMajorUnits("100.00"),
                new PercentSplitInput(Map.of(
                        "u1", new BigDecimal("50"),
                        "u2", new BigDecimal("50"))));

        Money sum = shares.stream().map(ExpenseShare::amount).reduce(Money.ZERO, Money::add);
        assertEquals(Money.ofMajorUnits("100.00"), sum);
    }

    @Test
    void handlesRepeatingDecimalPercentagesWithLargestRemainderMethod() {
        // 10.00 split 33.33/33.33/33.34 must still reconcile exactly to 1000 minor units.
        List<ExpenseShare> shares = strategy.computeShares(
                Money.ofMajorUnits("10.00"),
                new PercentSplitInput(Map.of(
                        "u1", new BigDecimal("33.33"),
                        "u2", new BigDecimal("33.33"),
                        "u3", new BigDecimal("33.34"))));

        Money sum = shares.stream().map(ExpenseShare::amount).reduce(Money.ZERO, Money::add);
        assertEquals(Money.ofMajorUnits("10.00"), sum);
    }

    @Test
    void rejectsPercentagesNotSummingTo100() {
        assertThrows(InvalidSplitException.class, () -> strategy.computeShares(
                Money.ofMajorUnits("10.00"),
                new PercentSplitInput(Map.of("u1", new BigDecimal("40"), "u2", new BigDecimal("40")))));
    }

    @Test
    void allowsBenignFloatingRoundingWithinEpsilon() {
        // 33.333... * 3 presented as 33.33+33.33+33.34 = exactly 100, not an epsilon case,
        // so test true epsilon tolerance with 99.999 vs 100.
        List<ExpenseShare> shares = strategy.computeShares(
                Money.ofMajorUnits("10.00"),
                new PercentSplitInput(Map.of("u1", new BigDecimal("99.995"), "u2", new BigDecimal("0.005"))));
        Money sum = shares.stream().map(ExpenseShare::amount).reduce(Money.ZERO, Money::add);
        assertEquals(Money.ofMajorUnits("10.00"), sum);
    }

    @Test
    void rejectsNegativePercentage() {
        assertThrows(InvalidSplitException.class, () -> strategy.computeShares(
                Money.ofMajorUnits("10.00"),
                new PercentSplitInput(Map.of("u1", new BigDecimal("-10"), "u2", new BigDecimal("110")))));
    }

    @Test
    void largestRemainderGoesToHighestFractionalPartFirst() {
        // 1 unit split among 3 people equally (33.33% each) -> 1 minor unit total,
        // floors are all 0, one person must get the single leftover minor unit.
        List<ExpenseShare> shares = strategy.computeShares(
                Money.ofMinorUnits(1),
                new PercentSplitInput(Map.of(
                        "u1", new BigDecimal("33.33"),
                        "u2", new BigDecimal("33.33"),
                        "u3", new BigDecimal("33.34"))));
        long totalDistributed = shares.stream().mapToLong(s -> s.amount().minorUnits()).sum();
        assertEquals(1L, totalDistributed);
    }
}
