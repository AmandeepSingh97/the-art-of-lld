package lld.elevatorsystem.scheduler;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.elevator.Elevator;
import lld.elevatorsystem.exception.NoElevatorAvailableException;
import lld.elevatorsystem.request.HallRequest;

import java.util.List;

/**
 * Production dispatch strategy: a nearest-car, direction-aware cost function
 * in the spirit of the "LOOK" algorithm used by real elevator dispatchers.
 *
 * <h2>Cost model</h2>
 * For a candidate elevator {@code e} and hall request {@code r} at floor
 * {@code f} wanting direction {@code d}:
 * <ol>
 *   <li><b>Idle car:</b> cost = |e.currentFloor - f|. Cheapest possible case
 *       — the car can turn directly toward the call.</li>
 *   <li><b>Car already moving toward {@code f} in direction {@code d}, and
 *       has not yet passed it</b> (e.g. moving up, currently below {@code f},
 *       and {@code d == UP}): cost = |e.currentFloor - f|. This call sits
 *       directly on the car's existing LOOK sweep — effectively free to
 *       add.</li>
 *   <li><b>Car moving away from {@code f}, or already passed it this
 *       sweep:</b> cost = (distance to the end of the current sweep) +
 *       (distance from that end back to {@code f}). The car must finish its
 *       sweep, reverse, and come back — modeled explicitly rather than just
 *       penalized with a constant, so the estimate stays accurate on very
 *       large buildings.</li>
 *   <li><b>Car in {@code MAINTENANCE}:</b> cost = {@link Integer#MAX_VALUE}
 *       (never selected unless literally the only option, which is then a
 *       {@link NoElevatorAvailableException}).</li>
 * </ol>
 * A small load penalty ({@code pendingStopCount}) and a starvation guard
 * (see {@link Elevator#consecutiveMovementTicks()}) are added on top; see
 * inline comments. Ties are broken by lowest elevator id for determinism
 * (required for reproducible tests).
 */
public final class NearestCarScanStrategy implements SchedulingStrategy {

    /** Ticks a car may keep sweeping the same direction before new same-direction work is deprioritized. */
    private static final int FAIRNESS_TICK_THRESHOLD = 25;
    /** Penalty added once a car has swept past the fairness threshold, in "floors". */
    private static final int FAIRNESS_PENALTY = 1000;
    /** Per-pending-stop load penalty so an already-busy car is mildly disfavored versus a quieter one at equal distance. */
    private static final int LOAD_PENALTY_PER_STOP = 1;

    @Override
    public Elevator selectElevator(HallRequest request, List<Elevator> elevators, long currentTick) {
        Elevator best = null;
        long bestCost = Long.MAX_VALUE;

        for (Elevator e : elevators) {
            long cost = cost(e, request);
            if (cost < bestCost || (cost == bestCost && best != null && e.id() < best.id())) {
                bestCost = cost;
                best = e;
            }
        }

        if (best == null || bestCost >= Integer.MAX_VALUE) {
            throw new NoElevatorAvailableException(
                    "No elevator available to serve hall call at floor " + request.floor());
        }
        return best;
    }

    private long cost(Elevator e, HallRequest request) {
        if (e.isInMaintenance()) {
            return Integer.MAX_VALUE;
        }

        int current = e.currentFloor();
        int target = request.floor();
        Direction carDirection = e.direction();
        long distance;

        if (carDirection == Direction.IDLE) {
            distance = Math.abs(current - target);
        } else if (carDirection == request.direction() && isAheadInSweep(current, target, carDirection)) {
            distance = Math.abs(current - target);
        } else {
            // Must finish current sweep to its end, reverse, then travel back to the target.
            int sweepEnd = carDirection == Direction.UP ? e.maxFloor() : e.minFloor();
            distance = Math.abs(current - sweepEnd) + Math.abs(sweepEnd - target);
        }

        long loadPenalty = (long) e.pendingStopCount() * LOAD_PENALTY_PER_STOP;
        long fairnessPenalty =
                (carDirection == request.direction() && e.consecutiveMovementTicks() > FAIRNESS_TICK_THRESHOLD)
                        ? FAIRNESS_PENALTY
                        : 0;

        return distance + loadPenalty + fairnessPenalty;
    }

    private boolean isAheadInSweep(int current, int target, Direction direction) {
        return direction == Direction.UP ? target >= current : target <= current;
    }
}
