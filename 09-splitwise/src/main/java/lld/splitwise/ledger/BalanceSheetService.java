package lld.splitwise.ledger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lld.splitwise.model.Expense;
import lld.splitwise.model.ExpenseShare;
import lld.splitwise.model.Money;
import lld.splitwise.model.Settlement;

/**
 * Owns the set of {@link BalanceSheet} instances (one per group, plus one for
 * direct/non-group expenses) and translates domain events ({@link Expense},
 * {@link Settlement}) into ledger mutations.
 *
 * <p>The map of context id -> {@link BalanceSheet} uses
 * {@link ConcurrentHashMap#computeIfAbsent} to lazily create a context's
 * ledger on first use without any explicit locking at this layer — creation
 * races are resolved atomically by the concurrent map itself, and each
 * {@link BalanceSheet} then manages its own internal locking as described in
 * its class javadoc.
 */
public final class BalanceSheetService {

    private final Map<String, BalanceSheet> ledgersByContext = new ConcurrentHashMap<>();

    private BalanceSheet ledgerFor(String contextId) {
        String key = contextId == null ? BalanceSheet.DIRECT_CONTEXT : contextId;
        return ledgersByContext.computeIfAbsent(key, BalanceSheet::new);
    }

    /**
     * Applies an expense to the ledger: the payer is owed money by every
     * other participant equal to their share (the payer's own share, if they
     * are also a participant, nets to zero and is skipped).
     */
    public void applyExpense(Expense expense) {
        BalanceSheet ledger = ledgerFor(expense.groupId());
        String payer = expense.paidByUserId();
        Map<String, Money> owedToPayer = new java.util.LinkedHashMap<>();
        for (ExpenseShare share : expense.shares()) {
            owedToPayer.put(share.userId(), share.amount());
        }
        // Applied as a single atomic batch under one write-lock acquisition
        // (see BalanceSheet#applyExpenseShares) so a concurrent reader can
        // never observe only some of the expense's participants credited.
        ledger.applyExpenseShares(payer, owedToPayer);
    }

    /**
     * Applies a settlement: {@code fromUserId} paid {@code toUserId}
     * {@code amount} outside the system, which reduces what {@code fromUserId}
     * owed {@code toUserId} by that amount (and increases it, i.e. flips the
     * direction, if they overpay beyond what was owed — overpayment is
     * allowed and simply creates a reverse debt, matching real-world intent).
     */
    public void applySettlement(Settlement settlement) {
        BalanceSheet ledger = ledgerFor(settlement.groupId());
        // fromUserId reduces their debt to toUserId by `amount`, equivalent to
        // recording that toUserId now owes fromUserId `amount` against the existing debt.
        ledger.recordDebt(settlement.toUserId(), settlement.fromUserId(), settlement.amount());
    }

    /**
     * Atomically validates that {@code fromUserId} currently owes
     * {@code toUserId} a strictly positive amount and, only if so, applies
     * the settlement reducing that debt by {@code amount} -- both steps
     * under one write-lock acquisition via {@link BalanceSheet#trySettle}.
     * This is what {@link lld.splitwise.service.SettlementService} uses
     * instead of a separate {@code getBalance} check followed by a separate
     * {@code applySettlement} call, specifically to close the
     * check-then-act race where two concurrent settlements of the same debt
     * could otherwise both pass validation before either applied its
     * reduction.
     *
     * @return {@code true} if a positive debt existed and was reduced; {@code false} otherwise.
     */
    public boolean trySettle(String contextId, String fromUserId, String toUserId, Money amount) {
        return ledgerFor(contextId).trySettle(fromUserId, toUserId, amount);
    }

    public Money getBalance(String contextId, String userId, String counterpartyId) {
        return ledgerFor(contextId).getBalance(userId, counterpartyId);
    }

    public Map<String, Money> getBalancesForUser(String contextId, String userId) {
        return ledgerFor(contextId).getBalancesForUser(userId);
    }

    public Map<String, Long> computeNetBalancePerUser(String contextId) {
        return ledgerFor(contextId).computeNetBalancePerUser();
    }

    /**
     * Aggregates the signed balance between two users across a specific set
     * of contexts (e.g. all groups they share, plus direct expenses). Reads
     * each context's {@link BalanceSheet} independently via its own read
     * lock — never holding more than one context's lock at a time — so this
     * cannot deadlock against concurrent writers of any single context.
     */
    public Money getAggregatedBalance(List<String> contextIds, String userId, String counterpartyId) {
        Money total = Money.ZERO;
        for (String contextId : contextIds) {
            total = total.add(getBalance(contextId, userId, counterpartyId));
        }
        return total;
    }
}
