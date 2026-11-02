package lld.elevatorsystem.request;

import lld.elevatorsystem.Direction;

/**
 * A unit of work that an {@link lld.elevatorsystem.elevator.Elevator} must
 * eventually service: stop at {@link #floor()}. Implemented by both
 * {@link HallRequest} (a landing button press, direction-aware) and
 * {@link CabinRequest} (a cabin button press, direction-agnostic).
 *
 * <p>Unifying these behind one interface lets the elevator's request queues
 * ({@code NavigableSet<Integer>}) store only floor numbers while the richer
 * {@code Request} objects live in a side-table used purely for scheduling
 * cost calculations and fairness (aging). This keeps the hot-path movement
 * logic (comparisons on plain {@code int}/{@code Integer}) allocation-light.
 */
public interface Request {

    int floor();

    /**
     * Logical creation time expressed in controller "ticks" (not wall-clock
     * time) so that scheduling/aging decisions are deterministic and
     * trivially testable without sleeping real threads.
     */
    long createdAtTick();

    /**
     * The direction a hall request wants to travel in; cabin requests have
     * no inherent direction preference of their own, so they report
     * {@link Direction#IDLE} here (the direction they ultimately cause the
     * elevator to move in is derived from relative floor position, not from
     * this method).
     */
    Direction requestedDirection();
}
