package lld.splitwise.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lld.splitwise.algorithm.DebtSimplifier;
import lld.splitwise.exception.InvalidSettlementException;
import lld.splitwise.ledger.BalanceSheetService;
import lld.splitwise.model.Money;
import lld.splitwise.model.Settlement;

/**
 * Orchestrates recording settle-up payments and computing debt-simplification
 * suggestions. Keeps the {@link DebtSimplifier} (pure algorithm, no I/O) and
 * {@link BalanceSheetService} (ledger state/locking) fully decoupled from each
 * other — this class is the only place that wires them together.
 */
public final class SettlementService {

    private final BalanceSheetService balanceSheetService;
    private final lld.splitwise.repository.SettlementRepository settlementRepository;
    private final DebtSimplifier debtSimplifier = new DebtSimplifier();

    public SettlementService(BalanceSheetService balanceSheetService,
                              lld.splitwise.repository.SettlementRepository settlementRepository) {
        this.balanceSheetService = balanceSheetService;
        this.settlementRepository = settlementRepository;
    }

    /**
     * Records that {@code fromUserId} paid {@code toUserId} {@code amount}
     * outside the system. Requires that {@code fromUserId} currently owes
     * {@code toUserId} a strictly positive amount (overpayment beyond that
     * amount is permitted and simply creates a reverse debt — e.g. rounding
     * generosity, or settling in one lump sum ahead of a new expense).
     */
    public Settlement recordSettlement(String groupId, String fromUserId, String toUserId, Money amount) {
        Settlement settlement = new Settlement(
                UUID.randomUUID().toString(), groupId, fromUserId, toUserId, amount, Instant.now());
        // Atomic check-and-apply: validates fromUserId currently owes toUserId
        // a positive amount and reduces it in one write-lock acquisition, so
        // two concurrent settlements of the same debt cannot both pass
        // validation before either applies (see BalanceSheet#trySettle).
        boolean applied = balanceSheetService.trySettle(groupId, fromUserId, toUserId, amount);
        if (!applied) {
            throw new InvalidSettlementException(
                    "%s does not currently owe %s anything; nothing to settle"
                            .formatted(fromUserId, toUserId));
        }
        settlementRepository.save(settlement);
        return settlement;
    }

    /**
     * Computes (but does not apply) the suggested minimal settlement plan for
     * a group, using {@link DebtSimplifier}. Callers typically present this
     * to users as "instead of N pairwise payments, make these K payments",
     * and each suggestion still needs to be confirmed via
     * {@link #recordSettlement} once the real-world payment actually happens.
     */
    public List<Settlement> suggestSimplifiedSettlements(String groupId) {
        var netBalances = balanceSheetService.computeNetBalancePerUser(groupId);
        return debtSimplifier.simplify(netBalances, () -> UUID.randomUUID().toString(), groupId);
    }
}
