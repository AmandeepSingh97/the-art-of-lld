package lld.splitwise.split;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lld.splitwise.exception.InvalidSplitException;
import lld.splitwise.model.ExpenseShare;
import lld.splitwise.model.Money;
import lld.splitwise.split.SplitInput.ExactSplitInput;

/**
 * Uses caller-supplied exact amounts per participant. Since {@link Money} is
 * exact integer minor units, validation is a simple exact-equality check
 * (no epsilon/rounding tolerance needed) against the expense total — this is
 * one of the concrete payoffs of avoiding floating point for money.
 */
public final class ExactSplitStrategy implements SplitStrategy {

    @Override
    public List<ExpenseShare> computeShares(Money totalAmount, SplitInput input) {
        if (!(input instanceof ExactSplitInput exactInput)) {
            throw new InvalidSplitException("ExactSplitStrategy requires an ExactSplitInput");
        }
        Map<String, Money> exactAmounts = exactInput.exactAmountsByUserId();

        Money sum = Money.ZERO;
        List<ExpenseShare> shares = new ArrayList<>(exactAmounts.size());
        for (Map.Entry<String, Money> entry : exactAmounts.entrySet()) {
            Money amount = entry.getValue();
            if (amount.isNegative()) {
                throw new InvalidSplitException(
                        "Exact share for user %s cannot be negative: %s".formatted(entry.getKey(), amount));
            }
            sum = sum.add(amount);
            shares.add(new ExpenseShare(entry.getKey(), amount));
        }

        if (!sum.equals(totalAmount)) {
            throw new InvalidSplitException(
                    "Exact shares sum to %s but expense total is %s".formatted(sum, totalAmount));
        }
        return shares;
    }
}
