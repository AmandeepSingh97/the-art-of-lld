package lld.snakeandladder.events;

import lld.snakeandladder.model.Player;

/**
 * Published every time a player's token advances from one cell to another
 * as the direct result of a dice roll (before any board jump is applied).
 */
public record PlayerMovedEvent(Player player, int fromPosition, int toPosition, int rolled) implements GameEvent {
}
