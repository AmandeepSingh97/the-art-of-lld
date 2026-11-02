package lld.snakeandladder.events;

/**
 * Observer interface (GoF Observer pattern) decoupling the game engine from
 * anything that needs to react to in-game events: a console logger, a UI
 * renderer, a metrics emitter, a WebSocket broadcaster, or a test assertion
 * collector.
 *
 * <p>Declared as a functional interface so tests and demos can register a
 * listener as a lambda, e.g. {@code game.addListener(event -> log.add(event))}.</p>
 */
@FunctionalInterface
public interface GameEventListener {
    void onEvent(GameEvent event);
}
