package lld.snakeandladder.rules;

/**
 * The simpler, more casual rule: any roll that reaches or exceeds the final
 * cell wins immediately, with the token clamped to the final cell (the
 * "overshoot" portion of the roll is simply discarded). This is the default
 * used by {@code GameBuilder} when no {@link WinCondition} is supplied.
 */
public final class OvershootClampWinCondition implements WinCondition {

    @Override
    public MoveOutcome evaluate(int currentPosition, int rolledTotal, int totalCells) {
        int target = currentPosition + rolledTotal;
        if (target >= totalCells) {
            return new MoveOutcome(totalCells, true, true);
        }
        return new MoveOutcome(target, true, false);
    }
}
