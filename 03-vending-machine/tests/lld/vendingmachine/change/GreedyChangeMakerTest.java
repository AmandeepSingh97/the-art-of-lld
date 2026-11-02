package lld.vendingmachine.change;

import lld.vendingmachine.domain.Denomination;
import lld.vendingmachine.domain.Money;
import lld.vendingmachine.exceptions.InsufficientChangeException;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GreedyChangeMakerTest {

    private final GreedyChangeMaker changeMaker = new GreedyChangeMaker();

    private Map<Denomination, Integer> ample() {
        Map<Denomination, Integer> available = new EnumMap<>(Denomination.class);
        for (Denomination d : Denomination.values()) {
            available.put(d, 100);
        }
        return available;
    }

    @Test
    void usesLargestDenominationsFirstWhenAmple() throws InsufficientChangeException {
        Map<Denomination, Integer> change = changeMaker.computeChange(Money.ofCents(85), ample());
        assertEquals(3, change.get(Denomination.QUARTER));
        assertEquals(1, change.get(Denomination.DIME));
        assertEquals(0, change.getOrDefault(Denomination.NICKEL, 0));
        assertEquals(0, change.getOrDefault(Denomination.PENNY, 0));
    }

    @Test
    void fallsBackToSmallerDenominationsWhenLargerOnesAreScarce() throws InsufficientChangeException {
        Map<Denomination, Integer> available = new EnumMap<>(Denomination.class);
        available.put(Denomination.QUARTER, 1); // only one quarter available
        available.put(Denomination.DIME, 10);
        available.put(Denomination.NICKEL, 10);
        available.put(Denomination.PENNY, 10);

        Map<Denomination, Integer> change = changeMaker.computeChange(Money.ofCents(45), available);
        long total = change.entrySet().stream()
                .mapToLong(e -> e.getKey().valueInCents() * e.getValue())
                .sum();
        assertEquals(45, total);
        assertEquals(1, change.get(Denomination.QUARTER));
    }

    @Test
    void throwsInsufficientChangeWhenNoCombinationIsFeasible() {
        Map<Denomination, Integer> available = new EnumMap<>(Denomination.class);
        available.put(Denomination.ONE_DOLLAR, 1); // cannot make 30 cents from a single $1 bill
        assertThrows(InsufficientChangeException.class,
                () -> changeMaker.computeChange(Money.ofCents(30), available));
    }

    @Test
    void zeroAmountProducesEmptyBreakdown() throws InsufficientChangeException {
        Map<Denomination, Integer> change = changeMaker.computeChange(Money.ZERO, ample());
        assertEquals(0, change.size());
    }
}
