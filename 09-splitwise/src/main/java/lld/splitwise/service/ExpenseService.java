package lld.splitwise.service;

import java.time.Instant;
import java.util.UUID;

import lld.splitwise.exception.DuplicateExpenseException;
import lld.splitwise.exception.NotAGroupMemberException;
import lld.splitwise.ledger.BalanceSheetService;
import lld.splitwise.model.Expense;
import lld.splitwise.model.Group;
import lld.splitwise.model.Money;
import lld.splitwise.model.SplitType;
import lld.splitwise.repository.ExpenseRepository;
import lld.splitwise.split.SplitInput;
import lld.splitwise.split.SplitStrategy;
import lld.splitwise.split.SplitStrategyFactory;

/**
 * Orchestrates "add an expense": validate participants, compute shares via
 * the appropriate {@link SplitStrategy}, persist the expense, then apply it
 * to the ledger. This class intentionally contains <b>no</b> splitting
 * arithmetic (delegated to {@link SplitStrategy}) and no ledger/locking
 * details (delegated to {@link BalanceSheetService}) — it is a thin
 * coordinator, which keeps each concern independently testable and keeps
 * this class short enough to not become a god class.
 *
 * <h2>Idempotency</h2>
 * Network retries are a fact of life (client times out waiting for a
 * response that actually succeeded, and retries the same "add expense"
 * request). Callers may supply an {@code idempotencyKey}; the first
 * successful call with a given key wins and all subsequent calls with the
 * same key return the <i>original</i> expense rather than creating a
 * duplicate. This is implemented via
 * {@link ExpenseRepository#registerIdempotencyKey}, which is backed by an
 * atomic {@code putIfAbsent} — see {@link lld.splitwise.repository.InMemoryExpenseRepository}.
 */
public final class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final UserService userService;
    private final GroupService groupService;
    private final BalanceSheetService balanceSheetService;

    public ExpenseService(ExpenseRepository expenseRepository, UserService userService,
                           GroupService groupService, BalanceSheetService balanceSheetService) {
        this.expenseRepository = expenseRepository;
        this.userService = userService;
        this.groupService = groupService;
        this.balanceSheetService = balanceSheetService;
    }

    /**
     * Adds an expense. If {@code groupId} is non-null, every participant
     * (payer included) must already be a member of the group.
     *
     * @param idempotencyKey nullable; when supplied, guarantees "exactly
     *                        once" expense creation across retried calls.
     */
    public Expense addExpense(String groupId, String description, Money amount, String paidByUserId,
                               SplitType splitType, SplitInput splitInput, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = expenseRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        userService.requireExists(paidByUserId);
        Group group = null;
        if (groupId != null) {
            group = groupService.getOrThrow(groupId);
            if (!group.isMember(paidByUserId)) {
                throw new NotAGroupMemberException(paidByUserId, groupId);
            }
        }

        SplitStrategy strategy = SplitStrategyFactory.forType(splitType);
        var shares = strategy.computeShares(amount, splitInput);

        if (group != null) {
            for (var share : shares) {
                if (!group.isMember(share.userId())) {
                    throw new NotAGroupMemberException(share.userId(), groupId);
                }
            }
        }

        String expenseId = UUID.randomUUID().toString();

        if (idempotencyKey != null) {
            boolean won = expenseRepository.registerIdempotencyKey(idempotencyKey, expenseId);
            if (!won) {
                // Another thread raced us and registered this key first; defer to it.
                return expenseRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> new DuplicateExpenseException(idempotencyKey));
            }
        }

        Expense expense = new Expense(expenseId, groupId, description, amount, paidByUserId, splitType, shares, Instant.now());
        expenseRepository.save(expense);
        balanceSheetService.applyExpense(expense);
        return expense;
    }
}
