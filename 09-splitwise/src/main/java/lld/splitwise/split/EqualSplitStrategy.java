package lld.splitwise.split;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import lld.splitwise.exception.InvalidSplitException;
import lld.splitwise.model.ExpenseShare;
import lld.splitwise.model.Money;
import lld.splitwise.split.SplitInput.EqualSplitInput;

/**
 * Divides the total as evenly as possible across N participants.
 *
 * <h2>Remainder distribution rule</h2>
 * {@code base = totalMinorUnits / n} (integer division, truncates toward zero),
 * {@code remainder = totalMinorUnits % n}. Every participant gets {@code base};
 * the first {@code remainder} participants (in the order supplied by the
 * caller — callers are expected to pass a stable, e.g. alphabetically sorted,
 * order so results are deterministic and reproducible) each get one extra
 * minor unit. Example: $10.00 (1000 minor units) split 3 ways ->
 * base=333, remainder=1 -> shares = [334, 333, 333], sum = 1000. This keeps
 * every individual share within 1 minor unit of perfectly equal while
 * guaranteeing exact reconciliation with the total.
 */
public final class EqualSplitStrategy implements SplitStrategy {

    @Override
    public List<ExpenseShare> computeShares(Money totalAmount, SplitInput input) {
        if (!(input instanceof EqualSplitInput equalInput)) {
            throw new InvalidSplitException("EqualSplitStrategy requires an EqualSplitInput");
        }
        List<String> participants = equalInput.participantUserIds();
        Set<String> distinct = new LinkedHashSet<>(participants);
        if (distinct.size() != participants.size()) {
            throw new InvalidSplitException("Duplicate participants in equal split: " + participants);
        }

        int n = participants.size();
        long totalMinor = totalAmount.minorUnits();
        long base = totalMinor / n;
        long remainder = totalMinor % n;

        List<ExpenseShare> shares = new ArrayList<>(n);
        int index = 0;
        for (String userId : participants) {
            long share = base + (index < remainder ? 1 : 0);
            shares.add(new ExpenseShare(userId, Money.ofMinorUnits(share)));
            index++;
        }
        return shares;
    }
}
