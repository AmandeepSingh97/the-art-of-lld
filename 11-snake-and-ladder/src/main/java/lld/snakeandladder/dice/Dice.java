package lld.snakeandladder.dice;

/**
 * Strategy interface (GoF Strategy pattern) for producing the value of a
 * single player's roll. Implementations decide how many faces a die has,
 * how many dice are combined, and where randomness comes from.
 *
 * <p>This abstraction is what lets the same {@code Game} engine run:
 * <ul>
 *   <li>a classic single six-sided die ({@link SingleDie}),</li>
 *   <li>a "roll two dice and sum them" variant ({@link CompositeDice}),</li>
 *   <li>a fully deterministic, pre-scripted sequence of rolls for unit
 *       tests ({@link FixedSequenceDice}), and</li>
 *   <li>a seeded-but-still-random die for reproducible simulations
 *       ({@link SingleDie#seeded(int, long)}),</li>
 * </ul>
 * without the engine itself knowing or caring which one is in play.</p>
 */
public interface Dice {

    /** Produces the next roll. Implementations decide the range of values. */
    int roll();

    /** The smallest value this strategy can ever produce. */
    int minPossibleRoll();

    /** The largest value this strategy can ever produce. */
    int maxPossibleRoll();
}
