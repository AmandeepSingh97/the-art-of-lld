package lld.elevatorsystem.state;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.door.ObstructionSensor;
import lld.elevatorsystem.elevator.Elevator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@code Elevator}/{@link ElevatorState} state machine directly
 * (no scheduler/controller involved) to verify the SCAN/LOOK movement rules:
 * sweep in the current direction visiting stops in floor order, reverse only
 * once the sweep is exhausted, and go idle only once both queues are empty.
 */
class ElevatorStateMachineTest {

    private Elevator newElevator() {
        return new Elevator(1, 0, 10, 8, ObstructionSensor.alwaysClear());
    }

    @Test
    void idleElevatorWithNoRequestsStaysIdle() {
        Elevator elevator = newElevator();
        for (int i = 0; i < 5; i++) {
            elevator.tick();
        }
        assertEquals("IDLE", elevator.stateLabel());
        assertEquals(0, elevator.currentFloor());
    }

    @Test
    void elevatorMovesTowardAndStopsAtASingleRequestedFloor() {
        Elevator elevator = newElevator();
        elevator.addStop(5);

        boolean doorOpened = false;
        for (int i = 0; i < 15 && !doorOpened; i++) {
            elevator.tick();
            if (elevator.stateLabel().equals("DOOR_OPEN")) {
                doorOpened = true;
            } else {
                assertEquals("MOVING_UP", elevator.stateLabel());
            }
        }
        assertEquals(5, elevator.currentFloor());
        assertEquals("DOOR_OPEN", elevator.stateLabel());
    }

    @Test
    void scanAlgorithmVisitsMultipleUpStopsInAscendingOrderWithoutReversing() {
        Elevator elevator = newElevator();
        // Out-of-order insertion; SCAN must still visit them 2, 5, 8 in that order.
        elevator.addStop(8);
        elevator.addStop(2);
        elevator.addStop(5);

        java.util.List<Integer> visitedInOrder = new java.util.ArrayList<>();
        for (int i = 0; i < 40 && visitedInOrder.size() < 3; i++) {
            elevator.tick();
            if (elevator.stateLabel().equals("DOOR_OPEN") && elevator.door().state()
                    == lld.elevatorsystem.DoorState.OPENING) {
                visitedInOrder.add(elevator.currentFloor());
            }
        }
        assertEquals(java.util.List.of(2, 5, 8), visitedInOrder);
    }

    @Test
    void elevatorReversesDirectionOnlyAfterUpSweepIsFullyDrained() {
        Elevator elevator = newElevator();
        elevator.addStop(6); // up stop
        for (int i = 0; i < 6; i++) {
            elevator.tick(); // reaches 6, door opens
        }
        // Drain the door cycle fully.
        for (int i = 0; i < 10 && !elevator.stateLabel().equals("IDLE"); i++) {
            elevator.tick();
        }
        assertEquals("IDLE", elevator.stateLabel());
        assertEquals(6, elevator.currentFloor());

        elevator.addStop(2); // now a genuine down stop relative to current floor 6
        boolean sawMovingDown = false;
        for (int i = 0; i < 10; i++) {
            elevator.tick();
            if (elevator.stateLabel().equals("MOVING_DOWN")) {
                sawMovingDown = true;
            }
        }
        assertTrue(sawMovingDown);
        assertEquals(2, elevator.currentFloor());
    }

    @Test
    void elevatorGoesToOppositeQueueOnceCurrentDirectionSweepIsExhausted() {
        Elevator elevator = newElevator();
        elevator.addStop(4);
        for (int i = 0; i < 15 && !elevator.stateLabel().equals("DOOR_OPEN"); i++) {
            elevator.tick();
        }
        assertEquals("DOOR_OPEN", elevator.stateLabel());
        assertEquals(4, elevator.currentFloor());
        // While the door is open at floor 4, a passenger elsewhere requests floor 1 (a down stop).
        elevator.addStop(1);
        for (int i = 0; i < 15 && !elevator.stateLabel().equals("MOVING_DOWN"); i++) {
            elevator.tick();
        }
        assertEquals("MOVING_DOWN", elevator.stateLabel());
    }

    @Test
    void addingStopAtCurrentFloorOpensDoorImmediatelyWithoutMoving() {
        Elevator elevator = newElevator();
        assertEquals(0, elevator.currentFloor());
        elevator.addStop(0);
        assertEquals("DOOR_OPEN", elevator.stateLabel());
        assertEquals(0, elevator.currentFloor());
    }

    @Test
    void elevatorNeverMovesPastConfiguredFloorBounds() {
        Elevator elevator = newElevator();
        elevator.addStop(10);
        for (int i = 0; i < 50; i++) {
            elevator.tick();
            assertTrue(elevator.currentFloor() <= 10 && elevator.currentFloor() >= 0);
        }
    }

    @Test
    void maintenanceElevatorDoesNotMoveEvenWithPendingStops() {
        Elevator elevator = newElevator();
        elevator.addStop(5);
        elevator.enterMaintenance();
        for (int i = 0; i < 10; i++) {
            elevator.tick();
        }
        assertEquals(0, elevator.currentFloor());
        assertTrue(elevator.isInMaintenance());
        assertTrue(elevator.hasPendingStops());
    }

    @Test
    void exitingMaintenanceResumesServiceOfPreservedStops() {
        Elevator elevator = newElevator();
        elevator.addStop(3);
        elevator.enterMaintenance();
        elevator.tick();
        elevator.exitMaintenance();
        for (int i = 0; i < 5; i++) {
            elevator.tick();
        }
        assertEquals(3, elevator.currentFloor());
    }
}
