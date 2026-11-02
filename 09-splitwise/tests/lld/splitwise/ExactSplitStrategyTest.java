package lld.splitwise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import lld.splitwise.exception.InvalidSplitException;
import lld.splitwise.model.ExpenseShare;
import lld.splitwise.model.Money;
import lld.splitwise.split.ExactSplitStrategy;
import lld.splitwise.split.SplitInput.ExactSplitInput;

class ExactSplitStrategyTest {

    private final ExactSplitStrategy strategy = new ExactSplitStrategy();

    @Test
    void acceptsExactAmountsThatSumToTotal() {
        List<ExpenseShare> shares = strategy.computeShares(
                Money.ofMajorUnits("90.00"),
                new ExactSplitInput(Map.of(
                        "u1", Money.ofMajorUnits("30.00"),
                        "u2", Money.ofMajorUnits("20.00"),
                        "u3", Money.ofMajorUnits("40.00"))));
        assertEquals(3, shares.size());
    }

    @Test
    void rejectsAmountsThatDoNotSumToTotal() {
        InvalidSplitException ex = assertThrows(InvalidSplitException.class, () -> strategy.computeShares(
                Money.ofMajorUnits("90.00"),
                new ExactSplitInput(Map.of(
                        "u1", Money.ofMajorUnits("30.00"),
                        "u2", Money.ofMajorUnits("20.00")))));
        assertEquals(true, ex.getMessage().contains("50.00") || ex.getMessage().contains("90.00"));
    }

    @Test
    void rejectsNegativeExactShare() {
        assertThrows(InvalidSplitException.class, () -> strategy.computeShares(
                Money.ofMajorUnits("10.00"),
                new ExactSplitInput(Map.of("u1", Money.ofMajorUnits("-5.00"), "u2", Money.ofMajorUnits("15.00")))));
    }

    @Test
    void offByOneMinorUnitStillRejected() {
        assertThrows(InvalidSplitException.class, () -> strategy.computeShares(
                Money.ofMinorUnits(1000),
                new ExactSplitInput(Map.of("u1", Money.ofMinorUnits(999)))));
    }
}
