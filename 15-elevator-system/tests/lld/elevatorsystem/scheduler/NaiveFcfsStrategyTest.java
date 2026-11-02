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

/**
 * Also demonstrates, with assertions (not just a printed demo), that the
 * naive round-robin policy ignores position entirely: it can route a call to
 * a far car even when a much nearer car is available.
 */
class NaiveFcfsStrategyTest {

    @Test
    void rotatesThroughElevatorsInOrderIgnoringPosition() {
        Elevator a = new Elevator(1, 0, 20, 8, ObstructionSensor.alwaysClear());
        Elevator b = new Elevator(2, 0, 20, 8, ObstructionSensor.alwaysClear());
        NaiveFcfsStrategy strategy = new NaiveFcfsStrategy();

        Elevator first = strategy.selectElevator(new HallRequest(5, Direction.UP, 0), List.of(a, b), 0);
        Elevator second = strategy.selectElevator(new HallRequest(5, Direction.UP, 0), List.of(a, b), 0);
        Elevator third = strategy.selectElevator(new HallRequest(5, Direction.UP, 0), List.of(a, b), 0);

        assertEquals(1, first.id());
        assertEquals(2, second.id());
        assertEquals(1, third.id());
    }

    @Test
    void canRouteToAFarCarEvenWhenANearerCarExists_unlikeTheScanStrategy() {
        Elevator near = new Elevator(1, 0, 20, 8, ObstructionSensor.alwaysClear());
        Elevator far = new Elevator(2, 0, 20, 8, ObstructionSensor.alwaysClear());
        far.addStop(19);
        for (int i = 0; i < 25; i++) {
            far.tick(); // drive 'far' to the opposite end of the building so it is genuinely far from floor 1
        }

        NaiveFcfsStrategy strategy = new NaiveFcfsStrategy();
        // First call consumes index 0 ('near', correctly).
        strategy.selectElevator(new HallRequest(1, Direction.UP, 0), List.of(near, far), 0);
        // Second call blindly rotates to index 1 ('far') even though it is now far away from floor 1.
        Elevator second = strategy.selectElevator(new HallRequest(1, Direction.UP, 0), List.of(near, far), 0);

        assertEquals(2, second.id(), "naive round-robin ignores car position entirely, unlike NearestCarScanStrategy");
    }

    @Test
    void skipsElevatorsInMaintenance() {
        Elevator inMaintenance = new Elevator(1, 0, 20, 8, ObstructionSensor.alwaysClear());
        inMaintenance.enterMaintenance();
        Elevator inService = new Elevator(2, 0, 20, 8, ObstructionSensor.alwaysClear());

        NaiveFcfsStrategy strategy = new NaiveFcfsStrategy();
        for (int i = 0; i < 3; i++) {
            Elevator chosen = strategy.selectElevator(new HallRequest(5, Direction.UP, 0), List.of(inMaintenance, inService), 0);
            assertEquals(2, chosen.id());
        }
    }

    @Test
    void throwsWhenEveryCarIsInMaintenance() {
        Elevator a = new Elevator(1, 0, 20, 8, ObstructionSensor.alwaysClear());
        Elevator b = new Elevator(2, 0, 20, 8, ObstructionSensor.alwaysClear());
        a.enterMaintenance();
        b.enterMaintenance();

        NaiveFcfsStrategy strategy = new NaiveFcfsStrategy();
        assertThrows(NoElevatorAvailableException.class,
                () -> strategy.selectElevator(new HallRequest(5, Direction.UP, 0), List.of(a, b), 0));
    }
}
