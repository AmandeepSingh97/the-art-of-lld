package lld.snakeandladder.model;

/**
 * Lifecycle state of a {@code Game}. Deliberately kept as a plain enum
 * rather than a full State design pattern (see {@code README.md §7 Design
 * Patterns} for why a heavier State-pattern implementation was considered
 * and rejected as overengineering for three states with no per-state
 * polymorphic behavior).
 */
public enum GameState {
    /** Players and board are configured, but no turn has been played yet. */
    NOT_STARTED,
    /** At least one turn has been played and no winner has emerged yet. */
    IN_PROGRESS,
    /** A winner has been determined; no further turns may be played. */
    FINISHED
}
