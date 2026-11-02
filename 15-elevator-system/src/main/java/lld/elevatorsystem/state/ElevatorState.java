package lld.elevatorsystem.state;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.elevator.Elevator;

/**
 * State pattern: encapsulates the behavior of an elevator car for exactly one
 * phase of its lifecycle (idle, moving up, moving down, door open,
 * maintenance). The {@link Elevator} context delegates all per-tick movement
 * decisions to its current state object instead of branching on an enum with
 * a giant switch — this is what lets {@code MAINTENANCE} be added or
 * {@code DOOR_OPEN}'s retry protocol be changed without touching any other
 * state's code (open/closed for extension).
 *
 * <p>Each concrete state is a stateless singleton (no per-elevator mutable
 * fields) — all mutable data lives on {@link Elevator} itself, so a single
 * {@code IdleState} instance can be safely shared by every elevator in the
 * building.
 */
public interface ElevatorState {

    /**
     * Advances the elevator by one discrete time unit and returns the state
     * the elevator should be in immediately afterward (which may be
     * {@code this} if nothing changed).
     */
    ElevatorState step(Elevator elevator);

    /** The direction an outside observer should associate with this state. */
    Direction direction();

    /** Human-readable name, used for logging/reporting/tests. */
    String label();
}
