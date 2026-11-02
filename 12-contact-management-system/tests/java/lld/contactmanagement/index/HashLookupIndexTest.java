package lld.contactmanagement.index;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashLookupIndexTest {

    @Test
    void lookupReturnsAllIdsRegisteredUnderAKey() {
        HashLookupIndex index = new HashLookupIndex();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        index.add("5551234567", a);
        index.add("5551234567", b);

        assertEquals(Set.of(a, b), index.lookup("5551234567"));
    }

    @Test
    void lookupOnUnknownKeyReturnsEmptySet() {
        HashLookupIndex index = new HashLookupIndex();
        assertTrue(index.lookup("nope").isEmpty());
    }

    @Test
    void removingTheLastIdForAKeyCleansUpTheEntryEntirely() {
        HashLookupIndex index = new HashLookupIndex();
        UUID a = UUID.randomUUID();
        index.add("k", a);

        index.remove("k", a);

        assertTrue(index.lookup("k").isEmpty());
    }

    @Test
    void removingOneOfMultipleIdsKeepsTheOthers() {
        HashLookupIndex index = new HashLookupIndex();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        index.add("k", a);
        index.add("k", b);

        index.remove("k", a);

        assertEquals(Set.of(b), index.lookup("k"));
    }
}
