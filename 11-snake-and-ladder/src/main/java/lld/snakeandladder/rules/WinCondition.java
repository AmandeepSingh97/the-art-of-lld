package lld.snakeandladder.rules;

/**
 * Strategy interface (GoF Strategy pattern) encapsulating what it means to
 * "win" and what happens when a roll would overshoot the final cell.
 *
 * <p>This is the single hook the whole design relies on to support the
 * "exact landing on 100 required" variant called out in the problem
 * statement, without touching {@code Game}, {@code Board}, {@code Dice},
 * or any other component. See {@link ExactLandingWinCondition} and
 * {@link OvershootClampWinCondition}.</p>
 */
public interface WinCondition {

    /**
     * Evaluates a single move attempt.
     *
     * @param currentPosition the player's position before this roll
     * @param rolledTotal     the value produced by the configured {@code Dice}
     * @param totalCells      the board's final cell number
     */
    MoveOutcome evaluate(int currentPosition, int rolledTotal, int totalCells);
}
