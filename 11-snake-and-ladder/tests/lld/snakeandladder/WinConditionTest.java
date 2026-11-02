package lld.snakeandladder;

import lld.snakeandladder.rules.ExactLandingWinCondition;
import lld.snakeandladder.rules.MoveOutcome;
import lld.snakeandladder.rules.OvershootClampWinCondition;
import lld.snakeandladder.rules.WinCondition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the two shipped {@link WinCondition} strategies, verifying the
 * exact boundary behavior at the final cell (the part of this problem most
 * often under-tested by candidates).
 */
class WinConditionTest {

    private static final int TOTAL_CELLS = 100;

    @Test
    void exactLanding_rejectsOvershoot() {
        WinCondition rule = new ExactLandingWinCondition();
        MoveOutcome outcome = rule.evaluate(97, 5, TOTAL_CELLS); // would land on 102
        assertFalse(outcome.moved());
        assertFalse(outcome.isWinningMove());
        assertEquals(97, outcome.newPosition(), "Overshooting roll must leave the player in place");
    }

    @Test
    void exactLanding_acceptsExactFinalCell() {
        WinCondition rule = new ExactLandingWinCondition();
        MoveOutcome outcome = rule.evaluate(97, 3, TOTAL_CELLS);
        assertTrue(outcome.moved());
        assertTrue(outcome.isWinningMove());
        assertEquals(100, outcome.newPosition());
    }

    @Test
    void exactLanding_allowsNonWinningIntermediateMove() {
        WinCondition rule = new ExactLandingWinCondition();
        MoveOutcome outcome = rule.evaluate(10, 4, TOTAL_CELLS);
        assertTrue(outcome.moved());
        assertFalse(outcome.isWinningMove());
        assertEquals(14, outcome.newPosition());
    }

    @Test
    void overshootClamp_winsOnExactFinalCell() {
        WinCondition rule = new OvershootClampWinCondition();
        MoveOutcome outcome = rule.evaluate(97, 3, TOTAL_CELLS);
        assertTrue(outcome.moved());
        assertTrue(outcome.isWinningMove());
        assertEquals(100, outcome.newPosition());
    }

    @Test
    void overshootClamp_winsAndClampsWhenRollExceedsFinalCell() {
        WinCondition rule = new OvershootClampWinCondition();
        MoveOutcome outcome = rule.evaluate(97, 6, TOTAL_CELLS); // would be 103
        assertTrue(outcome.moved());
        assertTrue(outcome.isWinningMove());
        assertEquals(100, outcome.newPosition(), "Overshoot must be clamped to the final cell");
    }

    @Test
    void overshootClamp_allowsNonWinningIntermediateMove() {
        WinCondition rule = new OvershootClampWinCondition();
        MoveOutcome outcome = rule.evaluate(50, 4, TOTAL_CELLS);
        assertTrue(outcome.moved());
        assertFalse(outcome.isWinningMove());
        assertEquals(54, outcome.newPosition());
    }
}
