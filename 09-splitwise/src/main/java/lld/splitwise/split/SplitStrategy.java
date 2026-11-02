package lld.splitwise.split;

import java.util.List;

import lld.splitwise.exception.InvalidSplitException;
import lld.splitwise.model.ExpenseShare;
import lld.splitwise.model.Money;

/**
 * Strategy pattern: computes how a total {@link Money} amount is divided among
 * participants. Each {@link lld.splitwise.model.SplitType} has exactly one
 * implementation, selected at runtime by {@link SplitStrategyFactory}.
 *
 * <h2>Why Strategy here (and not an {@code if/else} on an enum in the service)?</h2>
 * Splitting logic is (a) independently testable business logic with real edge
 * cases (rounding, remainder distribution, percentage validation), (b) a
 * closed set today but historically grows (Splitwise added "split by shares/
 * units" later), and (c) must not leak into {@code ExpenseService}, which
 * should only orchestrate "validate → compute shares → persist → update
 * ledger" without caring how shares are computed. The Strategy pattern lets a
 * new split type be added by writing one new class + one factory branch,
 * without touching existing strategies or the service (Open/Closed Principle).
 *
 * <h2>Critical shared invariant — exact reconciliation</h2>
 * Every implementation MUST return shares whose {@link Money} amounts sum
 * <b>exactly</b> to {@code totalAmount}. Because {@link Money} is integer
 * minor units, naive division (e.g. {@code total / n}) usually leaves a
 * remainder of 1-few minor units due to integer truncation. Each strategy is
 * responsible for deterministically distributing that remainder (documented
 * per-implementation) so the invariant holds exactly, every time — this is
 * enforced defensively again in the {@link lld.splitwise.model.Expense}
 * constructor, which throws if it does not.
 */
public interface SplitStrategy {

    /**
     * Computes each participant's share of {@code totalAmount}.
     *
     * @throws InvalidSplitException if the input is structurally invalid for
     *                                this strategy (e.g. exact amounts don't
     *                                sum to the total, percentages don't sum
     *                                to 100, duplicate participants, etc.)
     */
    List<ExpenseShare> computeShares(Money totalAmount, SplitInput input) throws InvalidSplitException;
}
