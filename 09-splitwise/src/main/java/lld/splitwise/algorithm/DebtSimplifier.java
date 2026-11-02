package lld.splitwise.algorithm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import lld.splitwise.model.Money;
import lld.splitwise.model.Settlement;

/**
 * Greedy max-heap "debt simplification" algorithm: given each user's net
 * balance in a context (positive = owed money overall, negative = owes money
 * overall), produces a small set of settlement transactions that zeroes out
 * every balance.
 *
 * <h2>Why this problem exists</h2>
 * Without simplification, settling up naively means every pairwise debtor
 * pays every pairwise creditor directly, which can require up to
 * {@code O(n^2)} transactions for {@code n} users. Most of those transactions
 * are redundant: if A owes B $10 and B owes C $10, there is no need for two
 * transfers — A can pay C directly and B is removed from the picture
 * entirely. "Debt simplification" nets all pairwise debts into one
 * <b>net balance per user</b> first (already done by
 * {@link lld.splitwise.ledger.BalanceSheet#computeNetBalancePerUser()}), then
 * finds a small set of transactions between <i>net creditors</i> and
 * <i>net debtors</i> that zeroes everyone out.
 *
 * <h2>Algorithm (greedy, two max-heaps)</h2>
 * <pre>
 * 1. Partition users with non-zero net balance into:
 *      creditors = max-heap by balance descending (they are owed money)
 *      debtors   = max-heap by |balance| descending (they owe money)
 * 2. While both heaps are non-empty:
 *      pop the largest creditor C and largest debtor D
 *      settle = min(C.amount, |D.amount|)
 *      emit Settlement(from = D.user, to = C.user, amount = settle)
 *      C.amount -= settle; D.amount += settle (D.amount is negative, moves toward 0)
 *      if C.amount > 0, push C back; if D.amount < 0, push D back
 * 3. Terminate when both heaps are empty (every balance is exactly zero).
 * </pre>
 *
 * <h2>Correctness proof (sketch)</h2>
 * <b>Invariant:</b> at every iteration, {@code sum(creditors' remaining amounts)
 * == sum(|debtors' remaining amounts|)}, because the ledger's total balance
 * across all users is always exactly zero (money is conserved: every expense
 * share that increases one user's debt increases another's credit by the same
 * amount, and this is enforced by {@code Expense}'s exact-reconciliation
 * invariant). Each iteration fully zeroes out at least one of the two popped
 * accounts (whichever had the smaller magnitude), so the number of accounts
 * with a non-zero balance strictly decreases by at least one every iteration.
 * Since there are at most {@code n} accounts, the loop terminates in at most
 * {@code n - 1} iterations (the very last iteration always zeroes out both
 * remaining accounts simultaneously, since their magnitudes must then be
 * equal by the invariant above). Termination with all balances at zero means
 * the produced settlement set, when applied, reconciles the entire ledger —
 * i.e. the output is always a <b>valid</b> solution.
 *
 * <h2>This greedy algorithm is optimal in transaction COUNT UPPER BOUND, but
 * NOT always GLOBALLY MINIMAL</h2>
 * The greedy approach guarantees at most {@code n - 1} transactions for
 * {@code n} participants with non-zero balance — already a large
 * improvement over the naive {@code O(n^2)}. However, finding the
 * mathematically minimum number of transactions ("optimal account
 * balancing") is a harder combinatorial problem: it is equivalent to finding
 * the partition of users into the maximum number of disjoint subsets that
 * each independently sum to zero, then solving each subset optimally — this
 * is NP-hard in general (it generalizes the partition / subset-sum problem).
 * LeetCode 465 "Optimal Account Balancing" is the canonical statement of the
 * exact version and is solved with exponential-time search (DFS/backtracking
 * over subsets, exponential in the number of non-zero balances, tractable
 * only for small {@code n}, roughly {@code n <= 10-12} in practice) — not
 * with this greedy heap.
 *
 * <p><b>Concrete, verified counterexample</b> (found by brute-force search
 * over small random instances, see repository history / STAFF_LEVEL_DEEP_DIVE.md):
 * balances {@code {U0: -8, U1: +6, U2: -2, U3: +3, U4: +4, U5: -3}} (6 users,
 * sum = 0). This greedy algorithm produces <b>5</b> transactions
 * (U0&rarr;U1:6, U5&rarr;U4:3, U0&rarr;U3:2, U2&rarr;U3:1, U2&rarr;U4:1), while
 * the true minimum is <b>4</b> transactions, achievable via a different
 * grouping. This is expected and acceptable for this system: we explicitly
 * trade a small, bounded amount of "extra" transactions for a fast,
 * deterministic, polynomial-time algorithm — see README "Trade-offs and
 * Alternatives" for the full justification of this choice over the exact
 * exponential algorithm.
 *
 * <h2>Complexity</h2>
 * Let {@code n} = number of users with non-zero net balance in the context.
 * <ul>
 *   <li><b>Time:</b> building both heaps is {@code O(n log n)} (or
 *       {@code O(n)} using {@code heapify}, though {@link PriorityQueue}'s
 *       bulk constructor does not guarantee this — in practice n is small
 *       enough that it does not matter). The main loop runs at most
 *       {@code n - 1} times, each iteration doing O(1) heap pops plus O(log n)
 *       pushes, so the loop is {@code O(n log n)}. Overall: {@code O(n log n)}.</li>
 *   <li><b>Space:</b> {@code O(n)} for the two heaps and the output list.</li>
 * </ul>
 */
public final class DebtSimplifier {

    private record Account(String userId, long amount) {
    }

    /**
     * @param netBalances userId -&gt; signed net balance in minor units
     *                    (positive = owed to them, negative = they owe).
     *                    Entries with a zero balance are ignored.
     * @param idGenerator supplies ids for generated {@link Settlement} records
     * @param contextId   the group (or direct-context) these settlements apply to
     */
    public List<Settlement> simplify(Map<String, Long> netBalances, SettlementIdGenerator idGenerator, String contextId) {
        PriorityQueue<Account> creditors = new PriorityQueue<>((a, b) -> Long.compare(b.amount, a.amount));
        PriorityQueue<Account> debtors = new PriorityQueue<>((a, b) -> Long.compare(Math.abs(b.amount), Math.abs(a.amount)));

        for (Map.Entry<String, Long> entry : netBalances.entrySet()) {
            long amount = entry.getValue();
            if (amount > 0) {
                creditors.add(new Account(entry.getKey(), amount));
            } else if (amount < 0) {
                debtors.add(new Account(entry.getKey(), amount));
            }
        }

        List<Settlement> settlements = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            Account creditor = creditors.poll();
            Account debtor = debtors.poll();

            long settleAmount = Math.min(creditor.amount, Math.abs(debtor.amount));

            settlements.add(new Settlement(
                    idGenerator.nextId(),
                    contextId,
                    debtor.userId(),
                    creditor.userId(),
                    Money.ofMinorUnits(settleAmount),
                    java.time.Instant.now()));

            long remainingCreditor = creditor.amount - settleAmount;
            long remainingDebtor = debtor.amount + settleAmount;

            if (remainingCreditor > 0) {
                creditors.add(new Account(creditor.userId(), remainingCreditor));
            }
            if (remainingDebtor < 0) {
                debtors.add(new Account(debtor.userId(), remainingDebtor));
            }
        }
        return settlements;
    }

    /** Minimal abstraction so callers can plug in UUIDs, sequence ids, etc. */
    public interface SettlementIdGenerator {
        String nextId();
    }
}
