package lld.snakeandladder;

import lld.snakeandladder.board.BoardBuilder;
import lld.snakeandladder.exceptions.InvalidBoardConfigurationException;
import lld.snakeandladder.model.Board;
import lld.snakeandladder.model.JumpType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Board} and {@link BoardBuilder}: construction validation,
 * single-hop resolution, and the cycle-detection safety net underpinning
 * {@link Board#resolveChained(int)}.
 */
class BoardTest {

    @Test
    void buildsAStandardTenByTenBoardWithConfiguredJumps() {
        Board board = new BoardBuilder()
                .withSize(10)
                .addLadder(2, 23)
                .addSnake(32, 10)
                .build();

        assertEquals(10, board.size());
        assertEquals(100, board.totalCells());
        assertEquals(2, board.jumps().size());
        assertTrue(board.jumpStartingAt(2).isPresent());
        assertEquals(JumpType.LADDER, board.jumpStartingAt(2).orElseThrow().type());
        assertEquals(JumpType.SNAKE, board.jumpStartingAt(32).orElseThrow().type());
    }

    @Test
    void resolveSingleHopAppliesLadder() {
        Board board = new BoardBuilder().withSize(10).addLadder(2, 23).build();
        assertEquals(23, board.resolveSingleHop(2));
    }

    @Test
    void resolveSingleHopAppliesSnake() {
        Board board = new BoardBuilder().withSize(10).addSnake(32, 10).build();
        assertEquals(10, board.resolveSingleHop(32));
    }

    @Test
    void resolveSingleHopIsNoOpWhenNoJumpAtCell() {
        Board board = new BoardBuilder().withSize(10).addLadder(2, 23).build();
        assertEquals(15, board.resolveSingleHop(15));
    }

    @Test
    void resolveChainedFollowsMultipleHopsUntilTerminal() {
        // 2 -> 23 (ladder), 23 -> 5 (snake): chained resolution should land on 5.
        Board board = new BoardBuilder()
                .withSize(10)
                .addLadder(2, 23)
                .addSnake(23, 5)
                .build();
        assertEquals(5, board.resolveChained(2));
        // Single-hop resolution, by contrast, stops at the first hop.
        assertEquals(23, board.resolveSingleHop(2));
    }

    @Test
    void rejectsLadderThatMovesBackward() {
        BoardBuilder builder = new BoardBuilder().withSize(10);
        assertThrows(IllegalArgumentException.class, () -> builder.addLadder(50, 10));
    }

    @Test
    void rejectsSnakeThatMovesForward() {
        BoardBuilder builder = new BoardBuilder().withSize(10);
        assertThrows(IllegalArgumentException.class, () -> builder.addSnake(10, 50));
    }

    @Test
    void rejectsDuplicateJumpStartCell() {
        BoardBuilder builder = new BoardBuilder().withSize(10).addLadder(5, 25);
        assertThrows(InvalidBoardConfigurationException.class, () -> builder.addSnake(5, 2));
    }

    @Test
    void rejectsJumpStartingAtEntryCell() {
        BoardBuilder builder = new BoardBuilder().withSize(10).addLadder(1, 10);
        assertThrows(InvalidBoardConfigurationException.class, builder::build);
    }

    @Test
    void rejectsJumpStartingAtFinalCell() {
        BoardBuilder builder = new BoardBuilder().withSize(10).addSnake(100, 5);
        assertThrows(InvalidBoardConfigurationException.class, builder::build);
    }

    @Test
    void rejectsJumpEndOutsideBoardRange() {
        BoardBuilder builder = new BoardBuilder().withSize(10).addLadder(5, 500);
        assertThrows(InvalidBoardConfigurationException.class, builder::build);
    }

    @Test
    void rejectsCyclicJumpConfiguration() {
        // 10 -> 20 (ladder), 20 -> 10 (snake) forms a 2-cycle.
        BoardBuilder builder = new BoardBuilder()
                .withSize(10)
                .addLadder(10, 20)
                .addSnake(20, 10);
        assertThrows(InvalidBoardConfigurationException.class, builder::build);
    }

    @Test
    void rejectsBoardSizeOutsideAllowedRange() {
        assertThrows(InvalidBoardConfigurationException.class, () -> new BoardBuilder().withSize(1).build());
        assertThrows(InvalidBoardConfigurationException.class, () -> new BoardBuilder().withSize(1000).build());
    }

    @Test
    void boardWithNoJumpsIsValid() {
        Board board = new BoardBuilder().withSize(10).build();
        assertFalse(board.jumpStartingAt(50).isPresent());
        assertTrue(board.jumps().isEmpty());
    }
}
