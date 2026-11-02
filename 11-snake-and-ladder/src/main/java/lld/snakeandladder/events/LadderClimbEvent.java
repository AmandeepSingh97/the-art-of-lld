package lld.snakeandladder.events;

import lld.snakeandladder.model.Player;

/** Published when a player's token lands on a ladder's base and climbs up. */
public record LadderClimbEvent(Player player, int fromCell, int toCell) implements GameEvent {
}
