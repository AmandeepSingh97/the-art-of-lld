package lld.snakeandladder.model;

import java.util.Map;
import java.util.Optional;

/**
 * An immutable, square {@code size x size} board whose cells are numbered
 * {@code 1..size*size} (cell 0 represents "not yet entered the board").
 * Holds the full set of {@link Jump}s (snakes and ladders) keyed by their
 * {@code start} cell for O(1) resolution.
 *
 * <p><b>Immutability:</b> once constructed via {@link lld.snakeandladder.board.BoardBuilder},
 * a {@code Board} never changes. This makes it trivially safe to share a
 * single {@code Board} instance across multiple concurrently-running games
 * (e.g. an in-memory cache of "standard 10x10 board" shared by many game
 * rooms) without any synchronization — see {@code README.md §11 Concurrency}.</p>
 */
public final class Board {

    private final int size;
    private final int totalCells;
    private final Map<Integer, Jump> jumpsByStart;

    /**
     * Intended to be called exclusively by
     * {@link lld.snakeandladder.board.BoardBuilder#build()} after full
     * validation, guaranteeing every {@code Board} instance in the system
     * is internally consistent. Public only because the builder lives in a
     * sibling package; application code should always go through the
     * builder rather than calling this constructor directly.
     */
    public Board(int size, Map<Integer, Jump> jumpsByStart) {
        this.size = size;
        this.totalCells = size * size;
        this.jumpsByStart = Map.copyOf(jumpsByStart);
    }

    /** The board's dimension (e.g. 10 for a classic 10x10 board). */
    public int size() {
        return size;
    }

    /** Total addressable cells, i.e. the winning cell number ({@code size * size}). */
    public int totalCells() {
        return totalCells;
    }

    /** Unmodifiable view of all configured jumps, keyed by start cell. */
    public Map<Integer, Jump> jumps() {
        return jumpsByStart;
    }

    public Optional<Jump> jumpStartingAt(int cell) {
        return Optional.ofNullable(jumpsByStart.get(cell));
    }

    /**
     * Resolves the final resting cell for a token that has just landed on
     * {@code rawLandingCell}, applying at most one jump.
     *
     * <p><b>Design choice — single-hop resolution only:</b> classic Snakes
     * and Ladders rules apply a jump once per landing, even if the jump's
     * destination happens to coincide with another jump's start (chained
     * jumps are not resolved automatically). {@link lld.snakeandladder.board.BoardBuilder}
     * additionally rejects configurations that would create a jump cycle,
     * so this method can never infinite-loop even if a future change
     * enables chaining (see {@link #resolveChained(int)} for the
     * extensibility hook).</p>
     */
    public int resolveSingleHop(int rawLandingCell) {
        Jump jump = jumpsByStart.get(rawLandingCell);
        return jump == null ? rawLandingCell : jump.end();
    }

    /**
     * Extensibility hook for a house-rule variant where jumps chain (landing
     * on a jump's end cell that is itself another jump's start cell
     * continues resolving). Safe against cycles because
     * {@code BoardBuilder} rejects cyclic jump graphs at construction time;
     * the cap at {@code totalCells} iterations is a second, defensive line
     * of protection against any future relaxation of that guarantee.
     */
    public int resolveChained(int rawLandingCell) {
        int current = rawLandingCell;
        for (int hops = 0; hops <= totalCells; hops++) {
            Jump jump = jumpsByStart.get(current);
            if (jump == null) {
                return current;
            }
            current = jump.end();
        }
        throw new IllegalStateException(
                "Jump chain did not terminate within " + totalCells
                        + " hops starting at cell " + rawLandingCell
                        + " — this indicates a cycle slipped past BoardBuilder validation.");
    }
}
