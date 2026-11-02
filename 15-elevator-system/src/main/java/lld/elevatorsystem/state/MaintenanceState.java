package lld.elevatorsystem.state;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.elevator.Elevator;

/**
 * The car is withdrawn from service (e.g. for technician inspection, or an
 * automatic safety trip). It does not move and does not open its door even
 * if stops are queued; those stops are preserved (not dropped) so that
 * calling {@link Elevator#exitMaintenance()} resumes service exactly where it
 * left off. Entering/leaving this state is an explicit operator action, not
 * something the scheduler decides — modeled as methods on {@code Elevator}
 * rather than requests, since it is an operational concern, not a passenger
 * request.
 */
public final class MaintenanceState implements ElevatorState {

    public static final MaintenanceState INSTANCE = new MaintenanceState();

    private MaintenanceState() {
    }

    @Override
    public ElevatorState step(Elevator elevator) {
        return this;
    }

    @Override
    public Direction direction() {
        return Direction.IDLE;
    }

    @Override
    public String label() {
        return "MAINTENANCE";
    }
}
