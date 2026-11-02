package lld.ttlcache.policy;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LruEvictionPolicyTest {

    @Test
    void evictionCandidateIsEmptyWhenNothingTracked() {
        LruEvictionPolicy<String> policy = new LruEvictionPolicy<>();

        assertEquals(Optional.empty(), policy.evictionCandidate());
    }

    @Test
    void evictionCandidateIsTheLeastRecentlyInsertedKeyWhenNothingAccessed() {
        LruEvictionPolicy<String> policy = new LruEvictionPolicy<>();

        policy.onInsert("a");
        policy.onInsert("b");
        policy.onInsert("c");

        assertEquals(Optional.of("a"), policy.evictionCandidate());
    }

    @Test
    void accessingAKeyMovesItToMostRecentlyUsed() {
        LruEvictionPolicy<String> policy = new LruEvictionPolicy<>();

        policy.onInsert("a");
        policy.onInsert("b");
        policy.onInsert("c");
        policy.onAccess("a"); // "a" is no longer the least-recently-used

        assertEquals(Optional.of("b"), policy.evictionCandidate());
    }

    @Test
    void onRemoveStopsTrackingKeySoItIsNeverReturnedAsACandidateAgain() {
        LruEvictionPolicy<String> policy = new LruEvictionPolicy<>();

        policy.onInsert("a");
        policy.onInsert("b");
        policy.onRemove("a");

        assertEquals(Optional.of("b"), policy.evictionCandidate());
    }

    @Test
    void clearRemovesAllBookkeeping() {
        LruEvictionPolicy<String> policy = new LruEvictionPolicy<>();

        policy.onInsert("a");
        policy.onInsert("b");
        policy.clear();

        assertTrue(policy.evictionCandidate().isEmpty());
    }
}
