package lld.ttlcache.policy;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LfuEvictionPolicyTest {

    @Test
    void evictionCandidateIsEmptyWhenNothingTracked() {
        LfuEvictionPolicy<String> policy = new LfuEvictionPolicy<>();

        assertEquals(Optional.empty(), policy.evictionCandidate());
    }

    @Test
    void evictionCandidateIsTheLeastFrequentlyAccessedKey() {
        LfuEvictionPolicy<String> policy = new LfuEvictionPolicy<>();

        policy.onInsert("a"); // frequency 1
        policy.onInsert("b"); // frequency 1
        policy.onAccess("a"); // frequency 2

        assertEquals(Optional.of("b"), policy.evictionCandidate());
    }

    @Test
    void tiesAtTheSameFrequencyBreakByInsertionOrder() {
        LfuEvictionPolicy<String> policy = new LfuEvictionPolicy<>();

        policy.onInsert("a"); // frequency 1, inserted first
        policy.onInsert("b"); // frequency 1, inserted second

        assertEquals(Optional.of("a"), policy.evictionCandidate());
    }

    @Test
    void repeatedAccessRaisesFrequencyAndChangesCandidate() {
        LfuEvictionPolicy<String> policy = new LfuEvictionPolicy<>();

        policy.onInsert("a");
        policy.onInsert("b");
        policy.onInsert("c");
        policy.onAccess("a");
        policy.onAccess("a"); // "a" now frequency 3
        policy.onAccess("b"); // "b" now frequency 2
        // "c" remains at frequency 1, the minimum

        assertEquals(Optional.of("c"), policy.evictionCandidate());
    }

    @Test
    void onRemoveStopsTrackingKeySoItIsNeverReturnedAsACandidateAgain() {
        LfuEvictionPolicy<String> policy = new LfuEvictionPolicy<>();

        policy.onInsert("a");
        policy.onInsert("b");
        policy.onRemove("a");

        assertEquals(Optional.of("b"), policy.evictionCandidate());
    }

    @Test
    void clearRemovesAllBookkeeping() {
        LfuEvictionPolicy<String> policy = new LfuEvictionPolicy<>();

        policy.onInsert("a");
        policy.clear();

        assertTrue(policy.evictionCandidate().isEmpty());
    }
}
