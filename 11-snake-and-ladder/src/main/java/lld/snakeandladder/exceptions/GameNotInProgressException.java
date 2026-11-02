package lld.snakeandladder.exceptions;

/**
 * Thrown when an operation that requires an in-progress game (for example,
 * {@code Game.playSingleTurn()}) is invoked while the game has not yet been
 * started, or has already finished. Extends {@link IllegalStateException}
 * because it signals a programming/usage error rather than a recoverable
 * business condition.
 */
public class GameNotInProgressException extends IllegalStateException {

    public GameNotInProgressException(String message) {
        super(message);
    }
}
