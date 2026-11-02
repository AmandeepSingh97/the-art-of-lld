package lld.elevatorsystem.controller;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.DoorState;
import lld.elevatorsystem.elevator.Elevator;

import java.util.Set;

/**
 * Immutable, read-only snapshot of one elevator's observable state at a given
 * controller tick — used for reporting/demo output and for test assertions
 * without exposing the live, mutable {@link Elevator} internals (and its
 * concurrent collections) to callers.
 */
public record ElevatorSnapshot(
        int id,
        int currentFloor,
        Direction direction,
        String stateLabel,
        DoorState doorState,
        Set<Integer> pendingStops,
        int occupancy,
        int capacity) {

    public static ElevatorSnapshot of(Elevator elevator) {
        return new ElevatorSnapshot(
                elevator.id(),
                elevator.currentFloor(),
                elevator.direction(),
                elevator.stateLabel(),
                elevator.door().state(),
                elevator.pendingStops(),
                elevator.occupancy(),
                elevator.capacity());
    }
}
