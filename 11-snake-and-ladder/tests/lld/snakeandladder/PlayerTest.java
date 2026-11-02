package lld.snakeandladder;

import lld.snakeandladder.model.Player;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Player}: identity-based equality, blank-name rejection,
 * and position mutation guards.
 */
class PlayerTest {

    @Test
    void startsAtPositionZero() {
        Player player = Player.of("Alice");
        assertEquals(0, player.position());
    }

    @Test
    void twoPlayersWithSameIdAreEqualRegardlessOfName() {
        Player a = Player.withId("p1", "Alice");
        Player b = Player.withId("p1", "Alicia");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void twoPlayersWithDifferentIdsAreNotEqualEvenWithSameName() {
        Player a = Player.withId("p1", "Alice");
        Player b = Player.withId("p2", "Alice");
        assertNotEquals(a, b);
    }

    @Test
    void generatedIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            assertTrue(ids.add(Player.of("P" + i).id()));
        }
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> Player.withId("id", "   "));
    }

    @Test
    void moveToRejectsNegativePosition() {
        Player player = Player.of("Alice");
        assertThrows(IllegalArgumentException.class, () -> player.moveTo(-1));
    }

    @Test
    void moveToUpdatesPosition() {
        Player player = Player.of("Alice");
        player.moveTo(42);
        assertEquals(42, player.position());
        assertFalse(player.position() == 0);
    }
}
