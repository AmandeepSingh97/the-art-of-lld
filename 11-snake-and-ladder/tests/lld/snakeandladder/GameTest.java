package lld.snakeandladder;

import lld.snakeandladder.board.BoardBuilder;
import lld.snakeandladder.dice.FixedSequenceDice;
import lld.snakeandladder.engine.Game;
import lld.snakeandladder.events.GameEvent;
import lld.snakeandladder.events.LadderClimbEvent;
import lld.snakeandladder.events.PlayerMovedEvent;
import lld.snakeandladder.events.PlayerWonEvent;
import lld.snakeandladder.events.TurnSkippedEvent;
import lld.snakeandladder.exceptions.GameDidNotTerminateException;
import lld.snakeandladder.exceptions.GameNotInProgressException;
import lld.snakeandladder.exceptions.InsufficientPlayersException;
import lld.snakeandladder.model.Board;
import lld.snakeandladder.model.GameState;
import lld.snakeandladder.model.Player;
import lld.snakeandladder.rules.ExactLandingWinCondition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@link Game}, exercising the full turn loop with
 * fully deterministic dice ({@link FixedSequenceDice}) so every assertion
 * is exact rather than probabilistic.
 */
class GameTest {

    private static Board plainBoard(int size) {
        return new BoardBuilder().withSize(size).build();
    }

    @Test
    void playSingleTurnThrowsBeforeGameIsStarted() {
        Game game = Game.builder()
                .withBoard(plainBoard(10))
                .addPlayer(Player.of("Alice"))
                .addPlayer(Player.of("Bob"))
                .build();

        assertEquals(GameState.NOT_STARTED, game.state());
        assertThrows(GameNotInProgressException.class, game::playSingleTurn);
    }

    @Test
    void playSingleTurnMovesCurrentPlayerAndRotatesToNext() {
        Player alice = Player.of("Alice");
        Player bob = Player.of("Bob");
        Game game = Game.builder()
                .withBoard(plainBoard(10))
                .addPlayer(alice)
                .addPlayer(bob)
                .withDice(FixedSequenceDice.of(3))
                .build();
        game.start();

        game.playSingleTurn();
        assertEquals(3, alice.position());
        assertEquals(0, bob.position(), "Bob should not have moved yet");

        game.playSingleTurn();
        assertEquals(3, bob.position());
        assertEquals(2, game.turnsTaken());
    }

    @Test
    void ladderClimbIsAppliedDuringTheSameTurnAsTheRoll() {
        Board board = new BoardBuilder().withSize(10).addLadder(3, 23).build();
        Player alice = Player.of("Alice");
        Player bob = Player.of("Bob");
        List<GameEvent> events = new ArrayList<>();

        Game game = Game.builder()
                .withBoard(board)
                .addPlayer(alice)
                .addPlayer(bob)
                .withDice(FixedSequenceDice.of(3))
                .addListener(events::add)
                .build();
        game.start();
        game.playSingleTurn();

        assertEquals(23, alice.position());
        assertEquals(2, events.size(), "Expected a PlayerMovedEvent followed by a LadderClimbEvent");
        assertTrue(events.get(0) instanceof PlayerMovedEvent);
        assertTrue(events.get(1) instanceof LadderClimbEvent);
        PlayerMovedEvent moved = (PlayerMovedEvent) events.get(0);
        assertEquals(0, moved.fromPosition());
        assertEquals(3, moved.toPosition());
        LadderClimbEvent climb = (LadderClimbEvent) events.get(1);
        assertEquals(3, climb.fromCell());
        assertEquals(23, climb.toCell());
    }

    @Test
    void reachingFinalCellEndsGameAndRecordsWinner() {
        Board board = plainBoard(3); // totalCells = 9
        Player alice = Player.of("Alice");
        Player bob = Player.of("Bob");
        List<GameEvent> events = new ArrayList<>();

        Game game = Game.builder()
                .withBoard(board)
                .addPlayer(alice)
                .addPlayer(bob)
                .withDice(FixedSequenceDice.of(9))
                .addListener(events::add)
                .build();

        Player winner = game.playUntilWinner();

        assertEquals(alice, winner);
        assertEquals(GameState.FINISHED, game.state());
        assertEquals(9, alice.position());
        assertTrue(game.winner().isPresent());
        assertEquals(alice, game.winner().orElseThrow());
        assertTrue(events.get(events.size() - 1) instanceof PlayerWonEvent);
        assertThrows(GameNotInProgressException.class, game::playSingleTurn);
    }

    @Test
    void exactLandingWinConditionSkipsOvershootingRollWithoutMovingPlayer() {
        Board board = plainBoard(4); // totalCells = 16
        Player alice = Player.of("Alice");
        Player bob = Player.of("Bob");
        List<GameEvent> events = new ArrayList<>();

        Game game = Game.builder()
                .withBoard(board)
                .addPlayer(alice)
                .addPlayer(bob)
                .withDice(FixedSequenceDice.of(20)) // always overshoots a 16-cell board
                .withWinCondition(new ExactLandingWinCondition())
                .addListener(events::add)
                .build();
        game.start();

        game.playSingleTurn();

        assertEquals(0, alice.position(), "Overshooting roll must not move the player");
        assertEquals(GameState.IN_PROGRESS, game.state());
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof TurnSkippedEvent);
    }

    @Test
    void playUntilWinnerThrowsWhenMaxTurnsIsExceeded() {
        Board board = plainBoard(4); // totalCells = 16
        Game game = Game.builder()
                .withBoard(board)
                .addPlayer(Player.of("Alice"))
                .addPlayer(Player.of("Bob"))
                .withDice(FixedSequenceDice.of(20)) // always overshoots; never a winner
                .withWinCondition(new ExactLandingWinCondition())
                .withMaxTurns(5)
                .build();

        assertThrows(GameDidNotTerminateException.class, game::playUntilWinner);
    }

    @Test
    void builderRejectsFewerThanTwoPlayers() {
        var builder = Game.builder()
                .withBoard(plainBoard(10))
                .addPlayer(Player.of("Alice"));
        assertThrows(InsufficientPlayersException.class, builder::build);
    }

    @Test
    void builderRequiresABoard() {
        var builder = Game.builder()
                .addPlayer(Player.of("Alice"))
                .addPlayer(Player.of("Bob"));
        assertThrows(NullPointerException.class, builder::build);
    }

    @Test
    void threePlayersRotateInRoundRobinOrder() {
        Player alice = Player.of("Alice");
        Player bob = Player.of("Bob");
        Player carol = Player.of("Carol");
        Game game = Game.builder()
                .withBoard(plainBoard(10))
                .addPlayer(alice)
                .addPlayer(bob)
                .addPlayer(carol)
                .withDice(FixedSequenceDice.of(2))
                .build();
        game.start();

        game.playSingleTurn();
        game.playSingleTurn();
        game.playSingleTurn();
        assertEquals(2, alice.position());
        assertEquals(2, bob.position());
        assertEquals(2, carol.position());

        game.playSingleTurn(); // back to Alice
        assertEquals(4, alice.position());
        assertFalse(bob.position() == 4);
    }
}
