package lld.elevatorsystem.scheduler;

import lld.elevatorsystem.elevator.Elevator;
import lld.elevatorsystem.exception.NoElevatorAvailableException;
import lld.elevatorsystem.request.HallRequest;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deliberately naive baseline strategy kept only for comparison in the
 * README/tests: assigns hall calls to elevators in strict round-robin order,
 * completely ignoring car position, direction, or load.
 *
 * <p>This is a "first-come-first-served among elevators" policy, not a
 * reflection of request order — a real FCFS elevator policy is rarely
 * articulated more precisely than this in practice, since without
 * position-awareness there is no principled way to do better than rotate
 * through the fleet. It is O(1) to evaluate, which is its only advantage; see
 * {@code README.md §10} for the average-wait-time comparison against
 * {@link NearestCarScanStrategy}.
 */
public final class NaiveFcfsStrategy implements SchedulingStrategy {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public Elevator selectElevator(HallRequest request, List<Elevator> elevators, long currentTick) {
        List<Elevator> inService = elevators.stream().filter(e -> !e.isInMaintenance()).toList();
        if (inService.isEmpty()) {
            throw new NoElevatorAvailableException(
                    "No elevator available to serve hall call at floor " + request.floor());
        }
        int index = Math.floorMod(counter.getAndIncrement(), inService.size());
        return inService.get(index);
    }
}
