package lld.snakeandladder.exceptions;

/**
 * Thrown by {@code Game.playUntilWinner()} when the configured safety limit
 * on the number of turns is exceeded without a winner emerging.
 *
 * <p>Snakes and Ladders terminates with probability 1 in theory (a game
 * confined to a cycle of snakes/ladders forever has vanishing probability as
 * turn count grows), but no finite, enforceable upper bound exists in the
 * general case. The engine therefore exposes a configurable {@code maxTurns}
 * safety valve and surfaces this exception instead of looping forever, which
 * would be unacceptable in a production system driving real request
 * threads.</p>
 */
public class GameDidNotTerminateException extends RuntimeException {

    public GameDidNotTerminateException(String message) {
        super(message);
    }
}
