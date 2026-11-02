package lld.snakeandladder.dice;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fully scripted {@link Dice} implementation that replays a fixed list of
 * rolls, cycling back to the start once exhausted.
 *
 * <p>This is the workhorse of the test suite: it lets a test engineer
 * script an exact sequence of events — "roll a 4 to land on a ladder, then
 * roll a 3 to overshoot the board" — without depending on probability at
 * all. Production code never uses this class; it exists purely to make
 * {@code Game} behavior deterministically testable, which is precisely the
 * point of extracting {@link Dice} as a Strategy in the first place.</p>
 *
 * <p><b>Thread safety:</b> the internal cursor is an {@link AtomicInteger}
 * so that, unlike {@link SingleDie}, this class is safe to share across
 * threads if a test ever needs that — though ordinary single-game usage
 * never requires it.</p>
 */
public final class FixedSequenceDice implements Dice {

    private final List<Integer> rolls;
    private final AtomicInteger cursor = new AtomicInteger(0);

    public FixedSequenceDice(List<Integer> rolls) {
        this.rolls = List.copyOf(Objects.requireNonNull(rolls, "rolls"));
        if (this.rolls.isEmpty()) {
            throw new IllegalArgumentException("FixedSequenceDice requires at least one scripted roll");
        }
        for (int roll : this.rolls) {
            if (roll < 1) {
                throw new IllegalArgumentException("Scripted rolls must be positive, got " + roll);
            }
        }
    }

    public static FixedSequenceDice of(Integer... rolls) {
        return new FixedSequenceDice(List.of(rolls));
    }

    @Override
    public int roll() {
        int index = cursor.getAndUpdate(i -> (i + 1) % rolls.size());
        return rolls.get(index);
    }

    @Override
    public int minPossibleRoll() {
        return rolls.stream().mapToInt(Integer::intValue).min().orElseThrow();
    }

    @Override
    public int maxPossibleRoll() {
        return rolls.stream().mapToInt(Integer::intValue).max().orElseThrow();
    }
}
