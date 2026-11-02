package lld.elevatorsystem.state;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.elevator.Elevator;

/**
 * The car is stopped at a floor with its door cycling open &rarr; hold &rarr;
 * (obstruction-safe) close. All of the obstruction-retry protocol lives in
 * {@link lld.elevatorsystem.door.Door}; this state simply ticks the door each
 * controller tick and, once it reports fully closed, decides where to go
 * next using the same "continue sweep, else reverse, else idle" rule as
 * {@link MovingUpState}/{@link MovingDownState}.
 */
public final class DoorOpenState implements ElevatorState {

    public static final DoorOpenState INSTANCE = new DoorOpenState();

    private DoorOpenState() {
    }

    @Override
    public ElevatorState step(Elevator elevator) {
        elevator.door().tick();
        if (!elevator.door().isClosed()) {
            return this;
        }
        if (!elevator.upStops().isEmpty()) {
            return MovingUpState.INSTANCE;
        }
        if (!elevator.downStops().isEmpty()) {
            return MovingDownState.INSTANCE;
        }
        return IdleState.INSTANCE;
    }

    @Override
    public Direction direction() {
        return Direction.IDLE;
    }

    @Override
    public String label() {
        return "DOOR_OPEN";
    }
}
