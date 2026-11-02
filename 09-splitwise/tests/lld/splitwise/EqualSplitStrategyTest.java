package lld.splitwise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import lld.splitwise.exception.InvalidSplitException;
import lld.splitwise.model.ExpenseShare;
import lld.splitwise.model.Money;
import lld.splitwise.split.EqualSplitStrategy;
import lld.splitwise.split.SplitInput.EqualSplitInput;

class EqualSplitStrategyTest {

    private final EqualSplitStrategy strategy = new EqualSplitStrategy();

    @Test
    void splitsEvenlyWhenDivisible() {
        List<ExpenseShare> shares = strategy.computeShares(
                Money.ofMajorUnits("90.00"),
                new EqualSplitInput(List.of("u1", "u2", "u3")));

        assertEquals(3, shares.size());
        for (ExpenseShare share : shares) {
            assertEquals(Money.ofMajorUnits("30.00"), share.amount());
        }
    }

    @Test
    void distributesRemainderToFirstParticipantsDeterministically() {
        // 10.00 / 3 = 3.33.. -> base 333, remainder 1 minor unit.
        List<ExpenseShare> shares = strategy.computeShares(
                Money.ofMajorUnits("10.00"),
                new EqualSplitInput(List.of("u1", "u2", "u3")));

        assertEquals(Money.ofMinorUnits(334), shares.get(0).amount());
        assertEquals(Money.ofMinorUnits(333), shares.get(1).amount());
        assertEquals(Money.ofMinorUnits(333), shares.get(2).amount());

        Money sum = shares.stream().map(ExpenseShare::amount).reduce(Money.ZERO, Money::add);
        assertEquals(Money.ofMajorUnits("10.00"), sum);
    }

    @Test
    void rejectsDuplicateParticipants() {
        assertThrows(InvalidSplitException.class, () -> strategy.computeShares(
                Money.ofMajorUnits("10.00"),
                new EqualSplitInput(List.of("u1", "u1"))));
    }

    @Test
    void rejectsEmptyParticipantList() {
        assertThrows(IllegalArgumentException.class, () -> new EqualSplitInput(List.of()));
    }

    @Test
    void sumOfSharesAlwaysEqualsTotalAcrossManyParticipantCounts() {
        for (int n = 1; n <= 23; n++) {
            List<String> participants = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) participants.add("u" + i);

            Money total = Money.ofMajorUnits("101.37");
            List<ExpenseShare> shares = strategy.computeShares(total, new EqualSplitInput(participants));
            Money sum = shares.stream().map(ExpenseShare::amount).reduce(Money.ZERO, Money::add);
            assertEquals(total, sum, "Sum mismatch for n=" + n);
        }
    }
}
