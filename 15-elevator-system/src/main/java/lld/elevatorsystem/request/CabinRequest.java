package lld.elevatorsystem.request;

import lld.elevatorsystem.Direction;

/**
 * An internal cabin request: a passenger already inside a specific elevator
 * pressed a destination-floor button. Because the passenger is already
 * committed to that car, cabin requests carry no scheduling choice — they
 * are simply inserted into that elevator's own stop queue.
 *
 * @param floor         destination floor
 * @param createdAtTick controller tick at which the button was pressed
 */
public record CabinRequest(int floor, long createdAtTick) implements Request {

    @Override
    public Direction requestedDirection() {
        return Direction.IDLE;
    }
}
