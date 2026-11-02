package lld.snakeandladder.events;

import lld.snakeandladder.model.Player;

/**
 * Published when a roll is rejected by the active {@code WinCondition}
 * (for example, an {@code ExactLandingWinCondition} rejecting a roll that
 * would overshoot the final cell) and the player's turn ends without
 * moving.
 */
public record TurnSkippedEvent(Player player, int atPosition, int rolled, String reason) implements GameEvent {
}
