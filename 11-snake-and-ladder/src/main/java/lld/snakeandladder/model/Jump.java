package lld.snakeandladder.model;

/**
 * A unified representation of both snakes and ladders: a one-hop transfer
 * from {@code start} to {@code end} applied the instant a player's token
 * lands exactly on {@code start}.
 *
 * <p><b>Why unify snakes and ladders into one concept?</b> Functionally,
 * a snake and a ladder are the same mechanic — "landing on cell X instantly
 * relocates you to cell Y" — differing only in the direction of travel and
 * the flavor text/graphics. Modelling them as two unrelated classes (or
 * worse, two parallel {@code Map<Integer,Integer>} fields on {@code Board})
 * would duplicate validation logic, duplicate resolution logic in the game
 * loop, and make it harder to add a third jump flavor later (e.g. a
 * "teleporter" variant cell). A single {@code Jump} record keyed by
 * {@code start} lets {@code Board} use one map and one resolution method
 * for both. The {@link JumpType} is retained purely so that observers
 * (UI/logging) can report "snake bite" vs. "ladder climb" distinctly —
 * it carries no behavioral weight in the resolution algorithm itself.</p>
 *
 * <p>This is an immutable value object (a {@code record}); equality and
 * hash code are structural, which is exactly what is needed when jumps are
 * stored in a {@code Set} during board validation to detect duplicate start
 * cells.</p>
 */
public record Jump(int start, int end, JumpType type) {

    public Jump {
        if (start <= 0 || end <= 0) {
            throw new IllegalArgumentException(
                    "Jump cells must be positive: start=" + start + ", end=" + end);
        }
        if (start == end) {
            throw new IllegalArgumentException(
                    "Jump start and end must differ (got " + start + " -> " + end + ")");
        }
        if (type == JumpType.LADDER && end <= start) {
            throw new IllegalArgumentException(
                    "Ladder must move forward: " + start + " -> " + end);
        }
        if (type == JumpType.SNAKE && end >= start) {
            throw new IllegalArgumentException(
                    "Snake must move backward: " + start + " -> " + end);
        }
    }

    /** Convenience factory for a ladder (start &lt; end). */
    public static Jump ladder(int start, int end) {
        return new Jump(start, end, JumpType.LADDER);
    }

    /** Convenience factory for a snake (start &gt; end). */
    public static Jump snake(int start, int end) {
        return new Jump(start, end, JumpType.SNAKE);
    }
}
