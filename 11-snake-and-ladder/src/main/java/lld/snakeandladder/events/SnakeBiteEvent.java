package lld.snakeandladder.events;

import lld.snakeandladder.model.Player;

/** Published when a player's token lands on a snake's head and slides down. */
public record SnakeBiteEvent(Player player, int fromCell, int toCell) implements GameEvent {
}
