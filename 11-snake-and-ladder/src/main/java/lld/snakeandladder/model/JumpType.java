package lld.snakeandladder.model;

/**
 * Discriminates the two flavors of {@link Jump}. A unified {@code Jump}
 * abstraction (rather than separate {@code Snake} and {@code Ladder}
 * classes) is one of the key design decisions of this problem — see
 * {@code README.md §4 Domain Modelling} for the rationale.
 */
public enum JumpType {
    /** Moves a player backward: {@code end < start}. */
    SNAKE,
    /** Moves a player forward: {@code end > start}. */
    LADDER
}
