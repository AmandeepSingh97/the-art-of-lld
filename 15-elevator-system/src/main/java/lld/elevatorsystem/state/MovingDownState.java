package lld.elevatorsystem.state;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.elevator.Elevator;

/**
 * Mirror image of {@link MovingUpState}: services {@link Elevator#downStops()}
 * (ordered descending) until the car reaches the bottom of this sweep or runs
 * out of downward stops, then reverses to {@link MovingUpState} if there is
 * upward work pending, or goes {@link IdleState} otherwise.
 */
public final class MovingDownState implements ElevatorState {

    public static final MovingDownState INSTANCE = new MovingDownState();

    private MovingDownState() {
    }

    @Override
    public ElevatorState step(Elevator elevator) {
        elevator.advanceFloor(Direction.DOWN);
        int floor = elevator.currentFloor();

        if (elevator.downStops().remove(floor)) {
            elevator.door().beginOpening();
            return DoorOpenState.INSTANCE;
        }

        boolean reachedBottom = floor <= elevator.minFloor();
        boolean nothingAheadGoingDown = elevator.downStops().isEmpty();
        if (reachedBottom || nothingAheadGoingDown) {
            if (!elevator.upStops().isEmpty()) {
                return MovingUpState.INSTANCE;
            }
            return IdleState.INSTANCE;
        }
        return this;
    }

    @Override
    public Direction direction() {
        return Direction.DOWN;
    }

    @Override
    public String label() {
        return "MOVING_DOWN";
    }
}
