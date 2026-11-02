package lld.snakeandladder.turn;

import lld.snakeandladder.model.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Manages round-robin turn order over an arbitrary number of players using
 * a {@link Deque} as a rotating queue.
 *
 * <p><b>Why a {@code Deque} instead of a plain index into a {@code List}?</b>
 * A deque naturally expresses "the current player moves to the back of the
 * line after their turn" in O(1), and trivially supports a future
 * extensibility requirement such as "a player who rolls a 6 gets an extra
 * turn" (skip the rotation for one cycle) or "remove a player who
 * disconnects" (removal from the middle is awkward with a deque, which is
 * an explicit, acknowledged trade-off — see {@code README.md §16 Trade-offs}
 * for the alternative considered and rejected: a circular index over a
 * {@code List<Player>}).</p>
 *
 * <p><b>Thread safety:</b> backed by {@link ArrayDeque}, which is
 * explicitly not thread-safe. This is intentional: a single {@code Game}
 * (and therefore its single {@code TurnManager}) is driven by one logical
 * turn-taking thread at a time, per the engine's documented concurrency
 * model.</p>
 */
public final class TurnManager {

    private final Deque<Player> order;

    public TurnManager(List<Player> players) {
        Objects.requireNonNull(players, "players");
        if (players.size() < 2) {
            throw new IllegalArgumentException("TurnManager requires at least 2 players, got " + players.size());
        }
        this.order = new ArrayDeque<>(players);
    }

    /** The player whose turn is next, without removing them from the queue. */
    public Player peekCurrent() {
        return order.peekFirst();
    }

    /**
     * Removes and returns the player at the front of the queue. The caller
     * is responsible for calling {@link #requeue(Player)} to give that
     * player their place back for the next round (or omitting that call if
     * the player has just won and should no longer take turns).
     */
    public Player pollCurrent() {
        Player player = order.pollFirst();
        if (player == null) {
            throw new IllegalStateException("No players remaining in the turn queue");
        }
        return player;
    }

    /** Places a player at the back of the queue, i.e. after everyone else's next turn. */
    public void requeue(Player player) {
        order.addLast(Objects.requireNonNull(player, "player"));
    }

    public int remainingPlayerCount() {
        return order.size();
    }
}
