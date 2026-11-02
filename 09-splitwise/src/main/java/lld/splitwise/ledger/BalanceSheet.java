package lld.splitwise.ledger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import lld.splitwise.model.Money;

/**
 * A pairwise "who-owes-whom" ledger scoped to a single context (one {@code
 * Group}, or the sentinel {@link #DIRECT_CONTEXT} for non-group/"friend"
 * expenses). This is the authoritative source of truth for balances; it is
 * derived entirely from the stream of {@link lld.splitwise.model.Expense}
 * and {@link lld.splitwise.model.Settlement} facts applied to it, and never
 * stores anything that cannot be recomputed by replaying that stream
 * (an important recovery property — see README "Failure Scenarios").
 *
 * <h2>Representation</h2>
 * For every pair of users with a non-zero balance we store exactly one entry,
 * keyed by {@link UnorderedUserPair}, whose value is a signed {@code long} of
 * minor units: positive means {@code pair.first} owes {@code pair.second};
 * negative means {@code pair.second} owes {@code pair.first}. Netting a pair
 * down to zero removes the map entry, so {@code balances.size()} is always
 * exactly the number of "edges" that would appear in a balance-graph UI.
 *
 * <h2>Thread-safety and lock granularity</h2>
 * Every mutation (from an expense touching N participants, or a settlement
 * touching 2) must be applied as a single atomic unit — otherwise a
 * concurrent reader could observe a torn/partial update (e.g. see the payer
 * credited against 2 of 3 participants). We use one {@link ReentrantReadWriteLock}
 * <b>per context</b> (per group, or the direct-expense ledger): all writers
 * take {@code writeLock()}, all readers take {@code readLock()}. This is a
 * deliberately coarse granularity:
 * <ul>
 *   <li><b>Why not a lock per user-pair (finer-grained)?</b> A single expense
 *       among N group members touches up to N-1 pairs simultaneously. Taking
 *       N-1 separate pair-locks requires a strict global lock-ordering
 *       protocol (e.g. always acquire in sorted user-id order) to avoid
 *       deadlock between two concurrent expenses that touch overlapping
 *       pairs in different orders. That is real, well-known complexity for
 *       marginal benefit here: expenses in the same group already serialize
 *       on nearly the same pair-set in practice, so pair-level locking
 *       wouldn't meaningfully increase parallelism within one group — it
 *       would only help across unrelated groups, which per-context locking
 *       already provides for free.</li>
 *   <li><b>Why not one global lock for the whole ledger (coarser)?</b> That
 *       would serialize expense additions across <i>unrelated</i> groups
 *       that share no users, which is an avoidable throughput bottleneck at
 *       scale (thousands of independent groups adding expenses concurrently).</li>
 *   <li><b>Why read/write (not a plain {@code synchronized} mutex)?</b> Balance
 *       queries ("show me my balance in this group") are far more frequent
 *       than writes (expense/settlement submissions) in a typical workload;
 *       a read-write lock lets many concurrent readers proceed without
 *       blocking each other, only serializing against the rarer writers.</li>
 * </ul>
 * Because exactly one {@code BalanceSheet} (one lock) is ever held at a time
 * per expense/settlement (an expense belongs to exactly one context), there
 * is no nested-lock acquisition within this class and therefore no deadlock
 * risk from lock ordering. Cross-context aggregation (e.g. "my total balance
 * with Bob across all groups") is handled by
 * {@link BalanceSheetService#getAggregatedBalance}, which acquires each
 * relevant context's read lock one at a time (never two at once), which is
 * likewise deadlock-free.
 */
public final class BalanceSheet {

    public static final String DIRECT_CONTEXT = "__DIRECT__";

    private final String contextId;
    private final Map<UnorderedUserPair, Long> balances = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public BalanceSheet(String contextId) {
        this.contextId = contextId;
    }

    public String contextId() {
        return contextId;
    }

    /**
     * Atomically records that {@code debtorUserId} owes {@code creditorUserId}
     * an additional {@code amount} (on top of whatever they already owed/were
     * owed). Pass a negative-equivalent by swapping debtor/creditor if you
     * need to reduce a debt (e.g. during a settlement).
     */
    public void recordDebt(String debtorUserId, String creditorUserId, Money amount) {
        if (amount.isZero()) {
            return;
        }
        lock.writeLock().lock();
        try {
            applyDebtLocked(debtorUserId, creditorUserId, amount);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Atomically applies every share of one expense in a single critical
     * section: {@code payerUserId} is credited against every other
     * participant's share, all under one write-lock acquisition. This is the
     * method {@link BalanceSheetService#applyExpense} uses instead of
     * calling {@link #recordDebt} once per participant, specifically so that
     * a concurrent reader can never observe a partially-applied expense
     * (e.g. the payer credited against only 2 of 3 participants so far).
     * Without this, N separate {@code recordDebt} calls -- each individually
     * atomic but not atomic as a group -- would leave exactly that kind of
     * torn-read window open.
     *
     * @param payerUserId the user who is owed money by every other entry in {@code shares}
     * @param shares      participant id -> amount owed to the payer (the
     *                    payer's own entry, if present, is skipped by the caller)
     */
    public void applyExpenseShares(String payerUserId, Map<String, Money> shares) {
        lock.writeLock().lock();
        try {
            for (Map.Entry<String, Money> entry : shares.entrySet()) {
                String participant = entry.getKey();
                if (participant.equals(payerUserId)) {
                    continue; // the payer's own share is not a debt to themselves
                }
                applyDebtLocked(participant, payerUserId, entry.getValue());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Atomically checks that {@code debtorUserId} currently owes
     * {@code creditorUserId} a strictly positive amount, and if so, reduces
     * that debt by {@code amount} (same semantics as settling up) -- all
     * under a single write-lock acquisition, so there is no
     * check-then-act window between validating the debt exists and applying
     * the reduction. Without this, two threads concurrently settling the
     * same debt could both pass a separate "is there a debt?" check before
     * either applied its reduction, over-settling the debt (see
     * STAFF_LEVEL_DEEP_DIVE.md Q3 for the race this closes).
     *
     * @return {@code true} if a positive debt existed and the settlement was applied;
     *         {@code false} if there was nothing (or a reversed debt) to settle, in which
     *         case no mutation occurs.
     */
    public boolean trySettle(String debtorUserId, String creditorUserId, Money amount) {
        lock.writeLock().lock();
        try {
            UnorderedUserPair pair = UnorderedUserPair.of(debtorUserId, creditorUserId);
            int debtorSign = pair.signFor(debtorUserId);
            long currentDebt = balances.getOrDefault(pair, 0L) * debtorSign;
            if (currentDebt <= 0) {
                return false;
            }
            applyDebtLocked(creditorUserId, debtorUserId, amount);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Must only be called while holding {@code lock.writeLock()}. */
    private void applyDebtLocked(String debtorUserId, String creditorUserId, Money amount) {
        if (amount.isZero()) {
            return;
        }
        UnorderedUserPair pair = UnorderedUserPair.of(debtorUserId, creditorUserId);
        int debtorSign = pair.signFor(debtorUserId);
        long delta = debtorSign * amount.minorUnits();
        long updated = balances.getOrDefault(pair, 0L) + delta;
        if (updated == 0L) {
            balances.remove(pair);
        } else {
            balances.put(pair, updated);
        }
    }

    /**
     * Returns the signed amount {@code userId} owes {@code counterpartyId}:
     * positive means {@code userId} owes {@code counterpartyId}; negative
     * means {@code counterpartyId} owes {@code userId}; zero means settled up.
     */
    public Money getBalance(String userId, String counterpartyId) {
        lock.readLock().lock();
        try {
            UnorderedUserPair pair = UnorderedUserPair.of(userId, counterpartyId);
            long stored = balances.getOrDefault(pair, 0L);
            int userSign = pair.signFor(userId);
            // stored is positive meaning "first owes second"; convert to "userId owes counterparty".
            return Money.ofMinorUnits(stored * userSign);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns a snapshot map of {@code counterpartyId -> signed amount userId
     * owes them} for every counterparty with a non-zero balance against
     * {@code userId} in this context.
     */
    public Map<String, Money> getBalancesForUser(String userId) {
        lock.readLock().lock();
        try {
            Map<String, Money> result = new LinkedHashMap<>();
            for (Map.Entry<UnorderedUserPair, Long> entry : balances.entrySet()) {
                UnorderedUserPair pair = entry.getKey();
                if (pair.first.equals(userId)) {
                    result.put(pair.second, Money.ofMinorUnits(entry.getValue()));
                } else if (pair.second.equals(userId)) {
                    result.put(pair.first, Money.ofMinorUnits(-entry.getValue()));
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns net balance per user across the whole context: {@code userId ->
     * sum of signed amounts owed to them (positive) or by them (negative)}.
     * Used as the input to debt simplification.
     */
    public Map<String, Long> computeNetBalancePerUser() {
        lock.readLock().lock();
        try {
            Map<String, Long> net = new LinkedHashMap<>();
            for (Map.Entry<UnorderedUserPair, Long> entry : balances.entrySet()) {
                UnorderedUserPair pair = entry.getKey();
                long firstOwesSecond = entry.getValue();
                // first owes second => first's net decreases, second's net increases.
                net.merge(pair.first, -firstOwesSecond, Long::sum);
                net.merge(pair.second, firstOwesSecond, Long::sum);
            }
            return net;
        } finally {
            lock.readLock().unlock();
        }
    }

}
