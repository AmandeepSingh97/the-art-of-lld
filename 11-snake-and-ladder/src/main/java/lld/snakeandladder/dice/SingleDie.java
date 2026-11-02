package lld.snakeandladder.dice;

import java.util.random.RandomGenerator;

/**
 * A single die with a configurable number of faces ({@code 1..faces}).
 *
 * <p><b>Determinism for testing:</b> {@link java.util.Random} has implemented
 * {@link RandomGenerator} since Java 17, so seeding a {@code Random} and
 * handing it to this class is sufficient to make an entire game's sequence
 * of rolls perfectly reproducible — essential for writing assertions like
 * "after turn 3, player A has landed on a snake" without flakiness. See
 * {@link #seeded(int, long)}.</p>
 *
 * <p><b>Thread safety:</b> {@link java.util.Random} is thread-safe internally,
 * but a single {@code SingleDie} is intended to be owned by exactly one
 * {@code Game} instance, consistent with the engine's documented
 * single-threaded-per-game concurrency model.</p>
 */
public final class SingleDie implements Dice {

    private final int faces;
    private final RandomGenerator randomGenerator;

    public SingleDie(int faces, RandomGenerator randomGenerator) {
        if (faces < 2) {
            throw new IllegalArgumentException("A die must have at least 2 faces, got " + faces);
        }
        this.faces = faces;
        this.randomGenerator = randomGenerator;
    }

    /** A standard six-sided die backed by a non-deterministic random source. */
    public static SingleDie standardSixSided() {
        return new SingleDie(6, new java.util.Random());
    }

    /**
     * A six-sided die backed by a seeded, deterministic random source —
     * use this in tests that need reproducible roll sequences.
     */
    public static SingleDie seeded(int faces, long seed) {
        return new SingleDie(faces, new java.util.Random(seed));
    }

    @Override
    public int roll() {
        return randomGenerator.nextInt(faces) + 1;
    }

    @Override
    public int minPossibleRoll() {
        return 1;
    }

    @Override
    public int maxPossibleRoll() {
        return faces;
    }
}
