package lld.splitwise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import lld.splitwise.algorithm.DebtSimplifier;
import lld.splitwise.model.Settlement;

class DebtSimplifierTest {

    private final DebtSimplifier simplifier = new DebtSimplifier();
    private final DebtSimplifier.SettlementIdGenerator idGen = () -> "s" + new Object().hashCode();

    @Test
    void producesNoTransactionsWhenAlreadyBalanced() {
        Map<String, Long> net = new HashMap<>();
        net.put("alice", 0L);
        net.put("bob", 0L);
        List<Settlement> result = simplifier.simplify(net, idGen, "g1");
        assertTrue(result.isEmpty());
    }

    @Test
    void simpleTwoPartyDebtProducesOneTransaction() {
        Map<String, Long> net = Map.of("alice", 1000L, "bob", -1000L);
        List<Settlement> result = simplifier.simplify(net, idGen, "g1");
        assertEquals(1, result.size());
        assertEquals("bob", result.get(0).fromUserId());
        assertEquals("alice", result.get(0).toUserId());
        assertEquals(1000L, result.get(0).amount().minorUnits());
    }

    @Test
    void resultingSettlementsExactlyZeroOutEveryNetBalance() {
        Map<String, Long> net = new HashMap<>();
        net.put("a", 1000L);
        net.put("b", 500L);
        net.put("c", -700L);
        net.put("d", -800L);

        List<Settlement> result = simplifier.simplify(net, idGen, "g1");
        assertSettlementsZeroOutBalances(net, result);
    }

    @Test
    void transactionCountNeverExceedsNMinusOne() {
        Map<String, Long> net = new HashMap<>();
        net.put("a", 1000L);
        net.put("b", 500L);
        net.put("c", -700L);
        net.put("d", -800L);
        List<Settlement> result = simplifier.simplify(net, idGen, "g1");
        assertTrue(result.size() <= net.size() - 1);
    }

    @Test
    void verifiedCounterexampleWhereGreedyIsNotGloballyOptimal() {
        // See DebtSimplifier javadoc: brute-force-verified instance where the
        // mathematical minimum is 4 transactions but this greedy algorithm
        // produces 5. This test asserts the greedy algorithm's actual,
        // documented (sub-optimal but valid) behavior, not an idealized one.
        Map<String, Long> net = new HashMap<>();
        net.put("U0", -800L);
        net.put("U1", 600L);
        net.put("U2", -200L);
        net.put("U3", 300L);
        net.put("U4", 400L);
        net.put("U5", -300L);

        List<Settlement> result = simplifier.simplify(net, idGen, "g1");

        // The greedy algorithm here takes 5 transactions (one less than the n-1=5 upper bound
        // ceiling is reached exactly), while the true optimum is 4 -- demonstrating it is a
        // valid but not globally-minimal simplification.
        assertEquals(5, result.size());
        assertSettlementsZeroOutBalances(net, result);
    }

    @Test
    void allSettlementAmountsArePositiveAndBetweenDistinctUsers() {
        Map<String, Long> net = Map.of("a", 300L, "b", 200L, "c", -500L);
        List<Settlement> result = simplifier.simplify(net, idGen, "g1");
        for (Settlement s : result) {
            assertTrue(s.amount().isPositive());
            assertTrue(!s.fromUserId().equals(s.toUserId()));
        }
    }

    private void assertSettlementsZeroOutBalances(Map<String, Long> initialNet, List<Settlement> settlements) {
        Map<String, Long> remaining = new HashMap<>(initialNet);
        for (Settlement s : settlements) {
            remaining.merge(s.fromUserId(), s.amount().minorUnits(), Long::sum);
            remaining.merge(s.toUserId(), -s.amount().minorUnits(), Long::sum);
        }
        for (long v : remaining.values()) {
            assertEquals(0L, v);
        }
    }
}
