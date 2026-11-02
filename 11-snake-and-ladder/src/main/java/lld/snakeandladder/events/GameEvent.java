package lld.snakeandladder.events;

/**
 * Sealed root of every event the {@code Game} engine can publish to
 * {@link GameEventListener}s. Using a sealed interface (Java 17 feature)
 * means the compiler enforces that every exhaustive {@code instanceof}
 * chain (or, on a newer Java with pattern-matching switches, a
 * {@code switch}) handling a {@code GameEvent} is kept in sync whenever a
 * new event type is added — a permits-list change is a compile error at
 * every non-exhaustive consumer, not a silent runtime gap.
 */
public sealed interface GameEvent
        permits PlayerMovedEvent, SnakeBiteEvent, LadderClimbEvent, TurnSkippedEvent, PlayerWonEvent {
}
