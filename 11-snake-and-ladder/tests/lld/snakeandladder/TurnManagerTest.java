package lld.snakeandladder;

import lld.snakeandladder.model.Player;
import lld.snakeandladder.turn.TurnManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link TurnManager}'s round-robin rotation semantics.
 */
class TurnManagerTest {

    @Test
    void rotatesThroughPlayersInInsertionOrder() {
        Player a = Player.withId("a", "Alice");
        Player b = Player.withId("b", "Bob");
        Player c = Player.withId("c", "Carol");
        TurnManager manager = new TurnManager(List.of(a, b, c));

        Player first = manager.pollCurrent();
        manager.requeue(first);
        Player second = manager.pollCurrent();
        manager.requeue(second);
        Player third = manager.pollCurrent();
        manager.requeue(third);
        Player fourth = manager.pollCurrent();

        assertEquals(a, first);
        assertEquals(b, second);
        assertEquals(c, third);
        assertEquals(a, fourth, "After one full round, rotation should return to the first player");
    }

    @Test
    void peekDoesNotRemovePlayerFromQueue() {
        Player a = Player.withId("a", "Alice");
        Player b = Player.withId("b", "Bob");
        TurnManager manager = new TurnManager(List.of(a, b));

        assertEquals(a, manager.peekCurrent());
        assertEquals(a, manager.peekCurrent(), "peekCurrent must be idempotent");
        assertEquals(2, manager.remainingPlayerCount());
    }

    @Test
    void rejectsFewerThanTwoPlayers() {
        Player a = Player.withId("a", "Alice");
        assertThrows(IllegalArgumentException.class, () -> new TurnManager(List.of(a)));
    }

    @Test
    void aWinnerWhoIsNotRequeuedLeavesTheQueueForRemainingPlayers() {
        Player a = Player.withId("a", "Alice");
        Player b = Player.withId("b", "Bob");
        Player c = Player.withId("c", "Carol");
        TurnManager manager = new TurnManager(List.of(a, b, c));

        manager.pollCurrent(); // Alice takes her turn and wins; not requeued.
        assertEquals(2, manager.remainingPlayerCount());
        assertEquals(b, manager.peekCurrent());
    }
}
