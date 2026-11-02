package lld.snakeandladder.rules;

/**
 * Outcome of evaluating a single dice roll against a player's current
 * position under some {@link WinCondition}.
 *
 * @param newPosition  the position the player should move to <b>before</b>
 *                      board-jump resolution is applied (board jumps are
 *                      resolved separately by {@code Board}; a win
 *                      condition never needs to know about snakes/ladders
 *                      because, by construction, no jump may start on the
 *                      final cell)
 * @param moved         whether the player actually advances this turn; a
 *                      variant may reject an overshooting roll and leave
 *                      the player in place (see {@link ExactLandingWinCondition})
 * @param isWinningMove whether {@code newPosition} constitutes reaching the
 *                      final cell and therefore ends the game
 */
public record MoveOutcome(int newPosition, boolean moved, boolean isWinningMove) {
}
