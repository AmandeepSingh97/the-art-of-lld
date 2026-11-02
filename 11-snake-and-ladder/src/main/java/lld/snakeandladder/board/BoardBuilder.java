package lld.snakeandladder.board;

import lld.snakeandladder.exceptions.InvalidBoardConfigurationException;
import lld.snakeandladder.model.Board;
import lld.snakeandladder.model.Jump;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builder (GoF Builder pattern) for constructing a validated, immutable
 * {@link Board}.
 *
 * <p>All structural invariants of the board (see {@code README.md §5 Domain
 * Invariants}) are enforced here, once, at construction time, rather than
 * being re-checked by every consumer of {@code Board}. This keeps
 * {@code Board} itself a trivial, trusted, immutable data holder.</p>
 *
 * <p>Not thread-safe: a single {@code BoardBuilder} instance is expected to
 * be used by one thread to assemble one board and then discarded. This is
 * the conventional contract for builders and is explicitly documented
 * because it is not inherited from any supertype.</p>
 */
public final class BoardBuilder {

    private static final int MIN_SIZE = 3;
    private static final int MAX_SIZE = 100;

    private int size = 10;
    private final Map<Integer, Jump> jumps = new HashMap<>();

    /** Sets the board dimension; total cells will be {@code size * size}. */
    public BoardBuilder withSize(int size) {
        this.size = size;
        return this;
    }

    public BoardBuilder addLadder(int start, int end) {
        return addJump(Jump.ladder(start, end));
    }

    public BoardBuilder addSnake(int start, int end) {
        return addJump(Jump.snake(start, end));
    }

    private BoardBuilder addJump(Jump jump) {
        if (jumps.containsKey(jump.start())) {
            throw new InvalidBoardConfigurationException(
                    "Duplicate jump start cell: " + jump.start()
                            + " already has a " + jumps.get(jump.start()).type());
        }
        jumps.put(jump.start(), jump);
        return this;
    }

    /**
     * Validates all accumulated configuration and produces an immutable
     * {@link Board}. Throws {@link InvalidBoardConfigurationException} on
     * any violation; the partially-built state of this builder is left
     * untouched so callers may fix the offending call and retry.
     */
    public Board build() {
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new InvalidBoardConfigurationException(
                    "Board size must be between " + MIN_SIZE + " and " + MAX_SIZE + ", got " + size);
        }
        int totalCells = size * size;

        for (Jump jump : jumps.values()) {
            validateCellRange(jump.start(), totalCells, "start");
            validateCellRange(jump.end(), totalCells, "end");
            if (jump.start() == 1) {
                throw new InvalidBoardConfigurationException(
                        "A jump cannot start at cell 1 (the entry square): " + jump);
            }
            if (jump.start() == totalCells) {
                throw new InvalidBoardConfigurationException(
                        "A jump cannot start at the final cell " + totalCells
                                + " (that cell wins the game): " + jump);
            }
        }

        detectCycles();

        return new Board(size, jumps);
    }

    private void validateCellRange(int cell, int totalCells, String label) {
        if (cell < 1 || cell > totalCells) {
            throw new InvalidBoardConfigurationException(
                    "Jump " + label + " cell " + cell + " is outside board range [1, " + totalCells + "]");
        }
    }

    /**
     * Guarantees {@link Board#resolveChained(int)} can never loop forever,
     * even though the default single-hop resolution never needs this
     * property. Detected via a standard "white/gray/black" DFS cycle
     * detection over the directed graph implied by {@code start -> end}
     * edges, restricted to nodes that are themselves jump starts (a jump
     * whose {@code end} is not itself a {@code start} is necessarily a
     * terminal node and cannot participate in a cycle).
     */
    private void detectCycles() {
        Set<Integer> visiting = new HashSet<>();
        Set<Integer> visited = new HashSet<>();
        for (Integer startCell : jumps.keySet()) {
            if (!visited.contains(startCell)) {
                detectCycleFrom(startCell, visiting, visited);
            }
        }
    }

    private void detectCycleFrom(int cell, Set<Integer> visiting, Set<Integer> visited) {
        if (visiting.contains(cell)) {
            throw new InvalidBoardConfigurationException(
                    "Jump configuration contains a cycle reachable from cell " + cell
                            + "; chained resolution would never terminate.");
        }
        if (visited.contains(cell)) {
            return;
        }
        Jump jump = jumps.get(cell);
        if (jump == null) {
            visited.add(cell);
            return;
        }
        visiting.add(cell);
        detectCycleFrom(jump.end(), visiting, visited);
        visiting.remove(cell);
        visited.add(cell);
    }
}
