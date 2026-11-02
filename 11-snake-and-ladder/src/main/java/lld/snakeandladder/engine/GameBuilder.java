package lld.snakeandladder.engine;

import lld.snakeandladder.dice.Dice;
import lld.snakeandladder.dice.SingleDie;
import lld.snakeandladder.events.GameEventListener;
import lld.snakeandladder.exceptions.InsufficientPlayersException;
import lld.snakeandladder.model.Board;
import lld.snakeandladder.model.Player;
import lld.snakeandladder.rules.OvershootClampWinCondition;
import lld.snakeandladder.rules.WinCondition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builder (GoF Builder pattern) for assembling a {@link Game}.
 *
 * <p>Centralizing construction here — rather than giving {@code Game} a
 * large public constructor or several overloaded constructors — means
 * adding a new optional collaborator (for example, a future
 * {@code TurnTimeoutPolicy}) only requires adding one method here and one
 * field, with zero changes to any existing call site that does not care
 * about the new option. This is the same Builder rationale used for
 * {@code BoardBuilder}, applied one layer up.</p>
 *
 * <p>Defaults are chosen to make the common case ("just give me a working
 * 2+ player game") a one-liner: a standard 10x10 board with no jumps unless
 * the caller supplies one, a standard six-sided die, and the more casual
 * {@link OvershootClampWinCondition}.</p>
 */
public final class GameBuilder {

    private static final int DEFAULT_MAX_TURNS = 10_000;

    private Board board;
    private final List<Player> players = new ArrayList<>();
    private Dice dice = SingleDie.standardSixSided();
    private WinCondition winCondition = new OvershootClampWinCondition();
    private final List<GameEventListener> listeners = new ArrayList<>();
    private int maxTurns = DEFAULT_MAX_TURNS;

    public GameBuilder withBoard(Board board) {
        this.board = Objects.requireNonNull(board, "board");
        return this;
    }

    public GameBuilder addPlayer(Player player) {
        this.players.add(Objects.requireNonNull(player, "player"));
        return this;
    }

    public GameBuilder addPlayers(List<Player> players) {
        this.players.addAll(Objects.requireNonNull(players, "players"));
        return this;
    }

    public GameBuilder withDice(Dice dice) {
        this.dice = Objects.requireNonNull(dice, "dice");
        return this;
    }

    public GameBuilder withWinCondition(WinCondition winCondition) {
        this.winCondition = Objects.requireNonNull(winCondition, "winCondition");
        return this;
    }

    public GameBuilder addListener(GameEventListener listener) {
        this.listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /**
     * Caps the number of turns {@link Game#playUntilWinner()} will attempt
     * before raising {@link lld.snakeandladder.exceptions.GameDidNotTerminateException}.
     */
    public GameBuilder withMaxTurns(int maxTurns) {
        if (maxTurns <= 0) {
            throw new IllegalArgumentException("maxTurns must be positive, got " + maxTurns);
        }
        this.maxTurns = maxTurns;
        return this;
    }

    public Game build() {
        Objects.requireNonNull(board, "board must be set via withBoard(...) before build()");
        if (players.size() < 2) {
            throw new InsufficientPlayersException(
                    "At least 2 players are required to start a game, got " + players.size());
        }
        return new Game(board, players, dice, winCondition, listeners, maxTurns);
    }
}
