package lld.elevatorsystem.controller;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.door.ObstructionSensor;
import lld.elevatorsystem.elevator.Elevator;
import lld.elevatorsystem.exception.ElevatorOutOfServiceException;
import lld.elevatorsystem.exception.InvalidFloorException;
import lld.elevatorsystem.exception.NoElevatorAvailableException;
import lld.elevatorsystem.scheduler.NaiveFcfsStrategy;
import lld.elevatorsystem.scheduler.NearestCarScanStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElevatorControllerTest {

    private ElevatorController newController() {
        List<Elevator> cars = List.of(
                new Elevator(1, 0, 10, 8, ObstructionSensor.alwaysClear()),
                new Elevator(2, 0, 10, 8, ObstructionSensor.alwaysClear()));
        return new ElevatorController(cars, new NearestCarScanStrategy());
    }

    @Test
    void hallCallIsAssignedAndEventuallyServiced() {
        ElevatorController controller = newController();
        Elevator chosen = controller.requestHallCall(7, Direction.UP);

        for (int i = 0; i < 20; i++) {
            controller.tick();
        }
        assertEquals(7, chosen.currentFloor());
    }

    @Test
    void cabinCallIsRoutedToTheNamedElevatorOnly() {
        ElevatorController controller = newController();
        controller.requestCabinCall(2, 9);

        for (int i = 0; i < 20; i++) {
            controller.tick();
        }
        assertEquals(9, controller.getElevator(2).currentFloor());
        assertEquals(0, controller.getElevator(1).currentFloor());
    }

    @Test
    void rejectsHallCallOutsideBuildingBounds() {
        ElevatorController controller = newController();
        assertThrows(InvalidFloorException.class, () -> controller.requestHallCall(99, Direction.UP));
        assertThrows(InvalidFloorException.class, () -> controller.requestHallCall(-1, Direction.DOWN));
    }

    @Test
    void rejectsHallCallWithIdleDirection() {
        ElevatorController controller = newController();
        assertThrows(InvalidFloorException.class, () -> controller.requestHallCall(5, Direction.IDLE));
    }

    @Test
    void rejectsCabinCallAgainstAnUnknownElevator() {
        ElevatorController controller = newController();
        assertThrows(NoSuchElementException.class, () -> controller.requestCabinCall(999, 5));
    }

    @Test
    void rejectsCabinCallAgainstAnElevatorInMaintenance() {
        ElevatorController controller = newController();
        controller.getElevator(1).enterMaintenance();
        assertThrows(ElevatorOutOfServiceException.class, () -> controller.requestCabinCall(1, 5));
    }

    @Test
    void throwsNoElevatorAvailableWhenEntireFleetIsInMaintenance() {
        ElevatorController controller = newController();
        controller.getElevator(1).enterMaintenance();
        controller.getElevator(2).enterMaintenance();
        assertThrows(NoElevatorAvailableException.class, () -> controller.requestHallCall(5, Direction.UP));
    }

    @Test
    void strategyCanBeSwappedAtRuntime() {
        ElevatorController controller = newController();
        controller.setStrategy(new NaiveFcfsStrategy());

        Elevator first = controller.requestHallCall(5, Direction.UP);
        Elevator second = controller.requestHallCall(5, Direction.UP);
        assertTrue(first.id() != second.id(), "naive round-robin must alternate elevators");
    }

    @Test
    void tickAdvancesTheSharedSimulationClockVisibleToAllElevators() {
        ElevatorController controller = newController();
        assertEquals(0, controller.currentTick());
        controller.tick();
        controller.tick();
        assertEquals(2, controller.currentTick());
    }
}
