package lld.snakeandladder.exceptions;

/**
 * Thrown when an attempt is made to start a game with fewer than the minimum
 * number of players required (2). Enforced at {@code GameBuilder.build()}
 * time so that an under-populated game can never reach the turn-management
 * loop.
 */
public class InsufficientPlayersException extends RuntimeException {

    public InsufficientPlayersException(String message) {
        super(message);
    }
}
