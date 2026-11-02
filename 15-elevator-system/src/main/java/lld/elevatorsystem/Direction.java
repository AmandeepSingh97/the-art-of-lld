package lld.elevatorsystem;

/**
 * Direction of travel for an elevator car, or the direction requested by a
 * passenger standing at a hall (landing) panel.
 *
 * <p>{@link #IDLE} is a valid state for an elevator (parked, no pending
 * requests) but is never a valid value for a hall call — a passenger must
 * press either the up or the down button.
 */
public enum Direction {
    UP,
    DOWN,
    IDLE;

    /**
     * @return the reverse of this direction; {@link #IDLE} reverses to itself.
     */
    public Direction opposite() {
        return switch (this) {
            case UP -> DOWN;
            case DOWN -> UP;
            case IDLE -> IDLE;
        };
    }
}
