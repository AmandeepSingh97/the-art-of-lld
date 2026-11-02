package lld.snakeandladder.engine;

import lld.snakeandladder.events.GameEvent;
import lld.snakeandladder.events.GameEventListener;
import lld.snakeandladder.events.LadderClimbEvent;
import lld.snakeandladder.events.PlayerMovedEvent;
import lld.snakeandladder.events.PlayerWonEvent;
import lld.snakeandladder.events.SnakeBiteEvent;
import lld.snakeandladder.events.TurnSkippedEvent;
import lld.snakeandladder.exceptions.GameDidNotTerminateException;
import lld.snakeandladder.exceptions.GameNotInProgressException;
import lld.snakeandladder.dice.Dice;
import lld.snakeandladder.model.Board;
import lld.snakeandladder.model.GameState;
import lld.snakeandladder.model.Jump;
import lld.snakeandladder.model.JumpType;
import lld.snakeandladder.model.Player;
import lld.snakeandladder.rules.MoveOutcome;
import lld.snakeandladder.rules.WinCondition;
import lld.snakeandladder.turn.TurnManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates one complete game of Snake and Ladder: owns the
 * {@link Board}, the player roster, the {@link TurnManager}, the active
 * {@link Dice} strategy, the active {@link WinCondition} strategy, and the
 * list of {@link GameEventListener} observers, and drives the per-turn game
 * loop.
 *
 * <p>This class is intentionally the only place that knows how all the
 * other components fit together — every other class in this package
 * structure is either a pure value/strategy object or a narrowly-scoped
 * collaborator. That keeps {@code Game} the single "god class" risk in the
 * design; see {@code README.md §7 Design Patterns} and
 * {@code STAFF_LEVEL_DEEP_DIVE.md} for a direct discussion of why that
 * trade-off was accepted rather than further decomposed.</p>
 *
 * <p><b>Concurrency model:</b> a {@code Game} instance is <b>not</b>
 * thread-safe and is not meant to be used concurrently by multiple threads
 * calling {@link #playSingleTurn()} at once — exactly one logical
 * "turn-taking" thread should drive a given game at a time (this mirrors
 * how a physical board game works: only one person rolls the dice at a
 * time). See {@code README.md §11 Concurrency and Thread Safety} for the
 * full justification, including what would need to change to support
 * concurrent access from multiple request-handling threads in a server
 * (e.g. one HTTP request per turn).</p>
 */
public final class Game {

    private final Board board;
    private final List<Player> players;
    private final TurnManager turnManager;
    private final Dice dice;
    private final WinCondition winCondition;
    private final List<GameEventListener> listeners;
    private final int maxTurns;

    private GameState state = GameState.NOT_STARTED;
    private Player winner;
    private int turnsTaken = 0;
    private int turnAttempts = 0;

    Game(Board board, List<Player> players, Dice dice, WinCondition winCondition,
         List<GameEventListener> listeners, int maxTurns) {
        this.board = board;
        this.players = List.copyOf(players);
        this.turnManager = new TurnManager(this.players);
        this.dice = dice;
        this.winCondition = winCondition;
        this.listeners = new ArrayList<>(listeners);
        this.maxTurns = maxTurns;
    }

    public static GameBuilder builder() {
        return new GameBuilder();
    }

    public GameState state() {
        return state;
    }

    public Optional<Player> winner() {
        return Optional.ofNullable(winner);
    }

    public List<Player> players() {
        return players;
    }

    public Board board() {
        return board;
    }

    public int turnsTaken() {
        return turnsTaken;
    }

    public void addListener(GameEventListener listener) {
        listeners.add(listener);
    }

    /** Transitions the game from {@code NOT_STARTED} to {@code IN_PROGRESS}. */
    public void start() {
        if (state != GameState.NOT_STARTED) {
            throw new GameNotInProgressException("Game has already been started (state=" + state + ")");
        }
        state = GameState.IN_PROGRESS;
    }

    /**
     * Plays exactly one player's turn: roll the dice, evaluate the win
     * condition, resolve any board jump, update state, publish events, and
     * rotate the turn queue.
     *
     * @throws GameNotInProgressException if the game has not been started
     *                                     via {@link #start()} or has
     *                                     already finished
     */
    public void playSingleTurn() {
        if (state != GameState.IN_PROGRESS) {
            throw new GameNotInProgressException(
                    "Cannot play a turn while game is in state " + state);
        }
        turnAttempts++;

        Player current = turnManager.pollCurrent();
        int rolled = dice.roll();
        int fromPosition = current.position();

        MoveOutcome outcome = winCondition.evaluate(fromPosition, rolled, board.totalCells());

        if (!outcome.moved()) {
            turnManager.requeue(current);
            publish(new TurnSkippedEvent(current, fromPosition, rolled,
                    "roll would overshoot the final cell " + board.totalCells()));
            return;
        }

        int afterDiceMove = outcome.newPosition();
        current.moveTo(afterDiceMove);
        publish(new PlayerMovedEvent(current, fromPosition, afterDiceMove, rolled));

        int finalPosition = applyBoardJumpIfAny(current, afterDiceMove);

        turnsTaken++;

        if (outcome.isWinningMove()) {
            current.moveTo(finalPosition);
            winner = current;
            state = GameState.FINISHED;
            publish(new PlayerWonEvent(current, turnsTaken));
            return;
        }

        turnManager.requeue(current);
    }

    /**
     * Repeatedly plays turns until a winner emerges or {@code maxTurns} is
     * exceeded. {@code maxTurns} bounds the number of turn <em>attempts</em>
     * (including turns rejected by the {@link WinCondition} for
     * overshooting), not just successful moves — a win condition such as
     * {@link lld.snakeandladder.rules.ExactLandingWinCondition} combined
     * with unlucky dice can otherwise reject every single roll forever
     * without ever advancing a player, which would make a counter of
     * "successful moves only" useless as a safety valve. See
     * {@link lld.snakeandladder.exceptions.GameDidNotTerminateException}
     * for the full rationale.
     */
    public Player playUntilWinner() {
        if (state == GameState.NOT_STARTED) {
            start();
        }
        while (state == GameState.IN_PROGRESS) {
            if (turnAttempts >= maxTurns) {
                throw new GameDidNotTerminateException(
                        "Game did not produce a winner within " + maxTurns + " turn attempts");
            }
            playSingleTurn();
        }
        return winner;
    }

    private int applyBoardJumpIfAny(Player player, int landedCell) {
        Optional<Jump> jump = board.jumpStartingAt(landedCell);
        if (jump.isEmpty()) {
            return landedCell;
        }
        Jump j = jump.get();
        player.moveTo(j.end());
        if (j.type() == JumpType.SNAKE) {
            publish(new SnakeBiteEvent(player, j.start(), j.end()));
        } else {
            publish(new LadderClimbEvent(player, j.start(), j.end()));
        }
        return j.end();
    }

    private void publish(GameEvent event) {
        for (GameEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }
}
