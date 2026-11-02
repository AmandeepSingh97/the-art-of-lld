package lld.elevatorsystem.state;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.elevator.Elevator;

/**
 * The car is parked with no pending requests. Idle is the only state from
 * which the elevator "decides" which direction to start sweeping in; once it
 * commits to {@code MOVING_UP} or {@code MOVING_DOWN} it will not reverse
 * until that sweep is exhausted (the core LOOK-algorithm guarantee — see
 * {@code MovingUpState}/{@code MovingDownState}).
 */
public final class IdleState implements ElevatorState {

    public static final IdleState INSTANCE = new IdleState();

    private IdleState() {
    }

    @Override
    public ElevatorState step(Elevator elevator) {
        if (!elevator.upStops().isEmpty()) {
            return MovingUpState.INSTANCE;
        }
        if (!elevator.downStops().isEmpty()) {
            return MovingDownState.INSTANCE;
        }
        return this;
    }

    @Override
    public Direction direction() {
        return Direction.IDLE;
    }

    @Override
    public String label() {
        return "IDLE";
    }
}
