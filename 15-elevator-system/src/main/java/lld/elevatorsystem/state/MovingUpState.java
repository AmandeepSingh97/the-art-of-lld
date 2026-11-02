package lld.elevatorsystem.state;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.elevator.Elevator;

/**
 * The car is travelling upward, servicing {@link Elevator#upStops()} in
 * ascending order (an O(log n) {@code NavigableSet} lookup per tick, never an
 * O(n) scan). This is the "SCAN" half of the SCAN/LOOK algorithm: the car
 * keeps moving in the same direction, picking up every pending stop in its
 * path, and only reverses ({@link MovingDownState}) once there is nothing
 * left ahead of it in this direction — this is precisely what gives LOOK its
 * throughput advantage over FCFS (see {@code README.md §10}).
 */
public final class MovingUpState implements ElevatorState {

    public static final MovingUpState INSTANCE = new MovingUpState();

    private MovingUpState() {
    }

    @Override
    public ElevatorState step(Elevator elevator) {
        elevator.advanceFloor(Direction.UP);
        int floor = elevator.currentFloor();

        if (elevator.upStops().remove(floor)) {
            elevator.door().beginOpening();
            return DoorOpenState.INSTANCE;
        }

        boolean reachedTop = floor >= elevator.maxFloor();
        boolean nothingAheadGoingUp = elevator.upStops().isEmpty();
        if (reachedTop || nothingAheadGoingUp) {
            if (!elevator.downStops().isEmpty()) {
                return MovingDownState.INSTANCE;
            }
            return IdleState.INSTANCE;
        }
        return this;
    }

    @Override
    public Direction direction() {
        return Direction.UP;
    }

    @Override
    public String label() {
        return "MOVING_UP";
    }
}
