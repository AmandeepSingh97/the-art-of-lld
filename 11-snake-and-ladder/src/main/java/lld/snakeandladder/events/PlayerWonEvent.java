package lld.snakeandladder.events;

import lld.snakeandladder.model.Player;

/** Published exactly once per game, when a player reaches the final cell. */
public record PlayerWonEvent(Player player, int turnsTaken) implements GameEvent {
}
