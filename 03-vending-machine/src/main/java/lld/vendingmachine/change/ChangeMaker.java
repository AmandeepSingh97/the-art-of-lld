package lld.vendingmachine.change;

import lld.vendingmachine.domain.Denomination;
import lld.vendingmachine.domain.Money;
import lld.vendingmachine.exceptions.InsufficientChangeException;

import java.util.Map;

/**
 * Strategy interface for computing a change breakdown. Kept separate from
 * {@code CashBox} so the algorithm can be swapped (e.g. greedy vs. dynamic
 * programming) without touching any caller — a direct application of the
 * Open/Closed Principle and the Strategy pattern.
 */
public interface ChangeMaker {

    /**
     * Computes a breakdown of denominations summing exactly to {@code amount},
     * never using more of a denomination than {@code available} allows.
     *
     * @param amount    the exact change amount owed, must be {@code >= 0}
     * @param available the denomination counts currently in the cash box
     * @return a map of denomination -> count to hand back, summing exactly to {@code amount}
     * @throws InsufficientChangeException if no valid combination exists within {@code available}
     */
    Map<Denomination, Integer> computeChange(Money amount, Map<Denomination, Integer> available)
            throws InsufficientChangeException;
}
