package lld.elevatorsystem.request;

import lld.elevatorsystem.Direction;

/**
 * An external hall (landing) call: a passenger standing at {@code floor}
 * pressed the {@code direction} button. Immutable value object.
 *
 * @param floor            the landing floor; validated by the controller
 *                          before construction
 * @param direction         must be {@link Direction#UP} or
 *                          {@link Direction#DOWN}; never {@code IDLE}
 * @param createdAtTick     controller tick at which this call was raised,
 *                          used for wait-time aging in the scheduler
 */
public record HallRequest(int floor, Direction direction, long createdAtTick) implements Request {

    public HallRequest {
        if (direction == Direction.IDLE) {
            throw new IllegalArgumentException("A hall call must request UP or DOWN, not IDLE");
        }
    }

    @Override
    public Direction requestedDirection() {
        return direction;
    }
}
