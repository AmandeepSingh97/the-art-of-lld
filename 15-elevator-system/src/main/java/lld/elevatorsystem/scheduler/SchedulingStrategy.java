package lld.elevatorsystem.scheduler;

import lld.elevatorsystem.elevator.Elevator;
import lld.elevatorsystem.exception.NoElevatorAvailableException;
import lld.elevatorsystem.request.HallRequest;

import java.util.List;

/**
 * Strategy pattern: decides which {@link Elevator} should answer a given
 * {@link HallRequest}. Isolating this behind an interface is what lets the
 * controller swap between {@link NearestCarScanStrategy} (production
 * default) and {@link NaiveFcfsStrategy} (a deliberately poor baseline used
 * to demonstrate — and in tests, measure — why the SCAN/LOOK-aware cost
 * function wins) without changing any dispatch/queueing code.
 */
public interface SchedulingStrategy {

    /**
     * @param request       the hall call to assign
     * @param elevators     all elevators in the building (candidates)
     * @param currentTick   the controller's current simulation tick, used by
     *                      strategies that factor in how long a request has
     *                      already waited
     * @return the elevator selected to serve {@code request}
     * @throws NoElevatorAvailableException if no elevator can accept the request
     *                                       (e.g. every car is in maintenance)
     */
    Elevator selectElevator(HallRequest request, List<Elevator> elevators, long currentTick);
}
