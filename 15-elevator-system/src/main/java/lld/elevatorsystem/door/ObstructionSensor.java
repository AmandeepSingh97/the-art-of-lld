package lld.elevatorsystem.door;

/**
 * Abstraction over the physical light-curtain / pressure-strip hardware that
 * detects an obstruction (a bag, a hand, a person) in the door's path.
 *
 * <p>Modeled as a single-method functional interface so that production code
 * can plug in a real hardware driver while tests can supply a deterministic
 * fake (e.g. "obstructed for the first 2 checks, then clear").
 */
@FunctionalInterface
public interface ObstructionSensor {

    /**
     * @return {@code true} if the door path is currently obstructed and a
     *         close must not proceed.
     */
    boolean isObstructed();

    /** A sensor that never reports an obstruction; the default in production wiring. */
    static ObstructionSensor alwaysClear() {
        return () -> false;
    }
}
