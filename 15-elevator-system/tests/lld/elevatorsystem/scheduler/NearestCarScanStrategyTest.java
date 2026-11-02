package lld.elevatorsystem.scheduler;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.door.ObstructionSensor;
import lld.elevatorsystem.elevator.Elevator;
import lld.elevatorsystem.exception.NoElevatorAvailableException;
import lld.elevatorsystem.request.HallRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NearestCarScanStrategyTest {

    private Elevator car(int id, int startFloor) {
        Elevator e = new Elevator(id, 0, 20, 8, ObstructionSensor.alwaysClear());
        if (startFloor > 0) {
            e.addStop(startFloor);
            for (int i = 0; i < startFloor + 5; i++) {
                e.tick();
            }
        }
        return e;
    }

    @Test
    void picksTheIdleCarClosestToTheRequestedFloor() {
        Elevator near = car(1, 2);
        Elevator far = car(2, 18);
        NearestCarScanStrategy strategy = new NearestCarScanStrategy();

        Elevator chosen = strategy.selectElevator(new HallRequest(3, Direction.UP, 0), List.of(near, far), 0);

        assertEquals(1, chosen.id());
    }

    @Test
    void prefersACarAlreadySweepingTowardTheFloorInTheSameDirectionOverAFartherIdleCar() {
        Elevator sweeping = new Elevator(1, 0, 20, 8, ObstructionSensor.alwaysClear());
        sweeping.addStop(15); // commits it to an upward sweep
        for (int i = 0; i < 3; i++) {
            sweeping.tick(); // now at floor 3, moving up, well on its way toward 15
        }

        Elevator idleFarther = new Elevator(2, 0, 20, 8, ObstructionSensor.alwaysClear());
        // left idle at floor 0

        NearestCarScanStrategy strategy = new NearestCarScanStrategy();
        HallRequest request = new HallRequest(18, Direction.UP, 0);
        Elevator chosen = strategy.selectElevator(request, List.of(sweeping, idleFarther), 0);

        // sweeping car: distance |3-18| = 15, already heading the right way -> cost 15 + 1 pending stop = 16.
        // idle car: distance |0-18| = 18 -> cost 18. Sweeping car clearly wins.
        assertEquals(1, chosen.id());
    }

    @Test
    void neverSelectsACarInMaintenance() {
        Elevator maintenance = car(1, 5);
        maintenance.enterMaintenance();
        Elevator available = car(2, 15);

        NearestCarScanStrategy strategy = new NearestCarScanStrategy();
        Elevator chosen = strategy.selectElevator(new HallRequest(6, Direction.UP, 0), List.of(maintenance, available), 0);

        assertEquals(2, chosen.id());
    }

    @Test
    void throwsWhenEveryCarIsInMaintenance() {
        Elevator a = car(1, 0);
        Elevator b = car(2, 10);
        a.enterMaintenance();
        b.enterMaintenance();

        NearestCarScanStrategy strategy = new NearestCarScanStrategy();
        assertThrows(NoElevatorAvailableException.class,
                () -> strategy.selectElevator(new HallRequest(5, Direction.UP, 0), List.of(a, b), 0));
    }

    @Test
    void fairnessGuardDeprioritizesACarThatHasBeenSweepingTheSameDirectionTooLong() {
        Elevator longSweeper = new Elevator(1, 0, 100, 8, ObstructionSensor.alwaysClear());
        longSweeper.addStop(90);
        // Drive it well past the fairness threshold (25 ticks) while still moving up.
        for (int i = 0; i < 30; i++) {
            longSweeper.tick();
        }
        assertEquals("MOVING_UP", longSweeper.stateLabel());

        Elevator freshIdle = new Elevator(3, 0, 100, 8, ObstructionSensor.alwaysClear());

        NearestCarScanStrategy strategy = new NearestCarScanStrategy();
        // A new UP request just ahead of longSweeper's current position would normally be "free" (cost ~= distance),
        // but the fairness penalty should push the dispatcher toward the fresh idle car instead once the
        // long-sweeping car has exceeded the threshold, even though the fresh idle car is farther away.
        HallRequest request = new HallRequest(longSweeper.currentFloor() + 2, Direction.UP, 0);
        Elevator chosen = strategy.selectElevator(request, List.of(longSweeper, freshIdle), 0);

        assertEquals(3, chosen.id(), "fairness penalty should favor the fresh car once the sweeping car is starving its opposite queue");
    }
}
