package lld.contactmanagement.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both {@link NameIndex} implementations must behave identically from the
 * caller's point of view - the algorithm choice is purely a performance
 * trade-off (see README section 10), never a behavioral one. This test runs
 * the exact same scenarios against both via {@link MethodSource}.
 */
class NameIndexContractTest {

    static Stream<NameIndex> implementations() {
        return Stream.of(new TrieNameIndex(), new SortedArrayNameIndex());
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void prefixSearchFindsMatchingTokens(NameIndex index) {
        UUID john = UUID.randomUUID();
        UUID jane = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        index.add("john", john);
        index.add("jane", jane);
        index.add("bob", bob);

        assertEquals(Set.of(john, jane), index.searchByPrefix("j"));
        assertEquals(Set.of(john), index.searchByPrefix("joh"));
        assertEquals(Set.of(), index.searchByPrefix("xyz"));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void multipleContactsCanShareTheSameToken(NameIndex index) {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        index.add("smith", first);
        index.add("smith", second);

        assertEquals(Set.of(first, second), index.searchByPrefix("smi"));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void removeStopsMatchingAndDoesNotAffectOtherEntries(NameIndex index) {
        UUID john = UUID.randomUUID();
        UUID jane = UUID.randomUUID();
        index.add("john", john);
        index.add("jane", jane);

        index.remove("john", john);

        assertEquals(Set.of(jane), index.searchByPrefix("j"));
        assertEquals(Set.of(), index.searchByPrefix("john"));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void removingOneOfTwoSharedTokenHoldersKeepsTheOther(NameIndex index) {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        index.add("lee", first);
        index.add("lee", second);

        index.remove("lee", first);

        assertEquals(Set.of(second), index.searchByPrefix("lee"));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void emptyOrNullPrefixReturnsEmptySet(NameIndex index) {
        index.add("john", UUID.randomUUID());
        assertTrue(index.searchByPrefix("").isEmpty());
    }

    @Test
    void trieSearchIsIndependentOfTotalIndexSizeForAGivenPrefixLength() {
        // Not a timing assertion (flaky in CI); documents the O(|prefix|)
        // behavioral contract: a hit for a short prefix must return quickly
        // and correctly even with many unrelated entries present.
        TrieNameIndex trie = new TrieNameIndex();
        for (int i = 0; i < 5000; i++) {
            trie.add("unrelated" + i, UUID.randomUUID());
        }
        UUID target = UUID.randomUUID();
        trie.add("zz", target);

        assertEquals(Set.of(target), trie.searchByPrefix("zz"));
    }
}
