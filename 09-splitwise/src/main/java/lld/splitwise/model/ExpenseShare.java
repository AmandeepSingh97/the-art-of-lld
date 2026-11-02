package lld.splitwise.model;

import java.util.Objects;

/**
 * One participant's computed share of an {@link Expense}.
 *
 * <p>This is the <b>output</b> of a split strategy computation: regardless of
 * whether the input was equal shares, exact amounts, or percentages, every
 * strategy normalizes to a list of {@code ExpenseShare}s whose {@link Money}
 * amounts sum <i>exactly</i> to the expense total (see
 * {@link lld.splitwise.split.SplitStrategy} javadoc for the remainder-distribution
 * rule that guarantees this invariant).
 *
 * @param userId the participant who owes this share
 * @param amount the amount this participant owes for the expense (always &gt;= 0)
 */
public record ExpenseShare(String userId, Money amount) {
    public ExpenseShare {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(amount, "amount");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("Share amount cannot be negative: " + amount);
        }
    }
}
