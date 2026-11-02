package lld.vendingmachine.change;

import lld.vendingmachine.domain.Denomination;
import lld.vendingmachine.domain.Money;
import lld.vendingmachine.exceptions.InsufficientChangeException;

import java.util.EnumMap;
import java.util.Map;

/**
 * Greedy change-making: at each step, take as many of the largest remaining
 * denomination as available and needed, then move to the next smaller one.
 *
 * <p><b>Correctness assumption:</b> greedy is only guaranteed to find a
 * feasible/minimal solution for a <em>canonical</em> denomination system.
 * The standard US system used here ({@code Denomination}: 2000, 1000, 500,
 * 100, 25, 10, 5, 1 cents) is canonical, so greedy is provably optimal and
 * correct whenever a solution exists at all. This is a well-known result
 * (see Pearson/Kozen's canonical coin system proofs) and is safe to state
 * confidently in an interview.</p>
 *
 * <p><b>What would break this:</b> if the machine's denomination set were
 * configurable to something non-canonical (e.g. {1, 3, 4}), greedy can return
 * a sub-optimal or outright infeasible result when a feasible one exists
 * (e.g. amount=6 with {1,3,4}: greedy picks 4+1+1=3 coins, optimal is 3+3=2
 * coins — still feasible here, but other sets can make greedy miss feasibility
 * entirely). The fix is a bounded coin-change dynamic program: let
 * {@code dp[v]} = minimum coins to form value {@code v} respecting per-
 * denomination availability caps, an O(amount * numDenominations) time,
 * O(amount) space algorithm. The {@link ChangeMaker} interface exists
 * precisely so that swap is a one-line change at the call site, not a
 * rewrite.</p>
 */
public final class GreedyChangeMaker implements ChangeMaker {

    @Override
    public Map<Denomination, Integer> computeChange(Money amount, Map<Denomination, Integer> available)
            throws InsufficientChangeException {
        long remaining = amount.cents();
        Map<Denomination, Integer> result = new EnumMap<>(Denomination.class);

        // Denomination.values() is declared in descending value order.
        for (Denomination d : Denomination.values()) {
            if (remaining == 0) {
                break;
            }
            int haveCount = available.getOrDefault(d, 0);
            long wantCount = remaining / d.valueInCents();
            long useCount = Math.min(haveCount, wantCount);
            if (useCount > 0) {
                result.put(d, (int) useCount);
                remaining -= useCount * d.valueInCents();
            }
        }

        if (remaining != 0) {
            throw new InsufficientChangeException(amount);
        }
        return result;
    }
}
