package lld.snakeandladder.rules;

/**
 * Enforces the "must land exactly on the final cell" house rule: a roll
 * that would overshoot the final cell is rejected outright and the player
 * does not move at all this turn (the standard convention — some table
 * variants instead bounce the token backward by the overshoot amount, which
 * could be added as a sibling strategy, e.g. {@code BounceBackWinCondition},
 * without touching this class).
 */
public final class ExactLandingWinCondition implements WinCondition {

    @Override
    public MoveOutcome evaluate(int currentPosition, int rolledTotal, int totalCells) {
        int target = currentPosition + rolledTotal;
        if (target > totalCells) {
            return new MoveOutcome(currentPosition, false, false);
        }
        boolean wins = target == totalCells;
        return new MoveOutcome(target, true, wins);
    }
}
