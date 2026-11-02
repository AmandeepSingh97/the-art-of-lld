package lld.elevatorsystem.elevator;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.door.Door;
import lld.elevatorsystem.door.ObstructionSensor;
import lld.elevatorsystem.state.DoorOpenState;
import lld.elevatorsystem.state.ElevatorState;
import lld.elevatorsystem.state.IdleState;
import lld.elevatorsystem.state.MaintenanceState;

import java.util.Collections;
import java.util.Comparator;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A single elevator car: the mutable "context" object of the State pattern
 * (see {@link ElevatorState}) and the owner of its own direction-aware
 * request queues.
 *
 * <h2>Request queues &mdash; why {@code ConcurrentSkipListSet}</h2>
 * {@code upStops} and {@code downStops} are {@link NavigableSet}s ordered by
 * floor number, which is exactly the SCAN/LOOK algorithm's data-structure
 * requirement: "give me the next stop in the current direction of travel"
 * reduces to {@link NavigableSet#ceiling(Object)} /
 * {@link NavigableSet#floor(Object)} &mdash; O(log n) &mdash; instead of an
 * O(n) scan over an unordered list every tick. A plain {@code TreeSet} would
 * give the same ordering and complexity, but it is not thread-safe: hall
 * calls and cabin requests are inserted concurrently from arbitrary request
 * threads (e.g. multiple floors pressing their button "simultaneously") while
 * this elevator's own single movement thread iterates/removes from the same
 * set every tick. {@link ConcurrentSkipListSet} provides the identical
 * {@code NavigableSet} API and ordering/complexity guarantees but is
 * lock-free and safe for concurrent reads and writes, and — crucially — its
 * iterators are <i>weakly consistent</i> rather than fail-fast, so a writer
 * thread inserting a stop never throws {@code ConcurrentModificationException}
 * against the reader. See {@code README.md §10} for the full alternatives
 * comparison.
 *
 * <h2>Threading model</h2>
 * An {@code Elevator} is designed as a <b>single-writer, multi-reader</b>
 * object:
 * <ul>
 *   <li><b>Writers to the stop queues</b> may be any number of concurrent
 *       dispatcher/controller threads calling {@link #addStop(int)}; the
 *       underlying {@code ConcurrentSkipListSet} makes this safe without any
 *       elevator-level lock.</li>
 *   <li><b>The state machine itself</b> ({@link #currentFloor}, {@link #state},
 *       the {@link Door}) is only ever advanced by {@link #tick()}, which the
 *       owning {@code ElevatorController} guarantees is invoked by exactly one
 *       thread at a time per elevator (the controller's single simulation
 *       clock). {@code tick()} is additionally marked {@code synchronized} as
 *       a defensive second line of defense — it costs nothing under the
 *       documented single-clock usage and prevents silent data races if a
 *       caller ever violates that contract (e.g. a future per-elevator
 *       worker-thread refactor).</li>
 * </ul>
 */
public final class Elevator {

    private final int id;
    private final int minFloor;
    private final int maxFloor;
    private final int capacity;
    private final Door door;

    /** Floors to visit while travelling upward, nearest first. */
    private final NavigableSet<Integer> upStops = new ConcurrentSkipListSet<>();
    /** Floors to visit while travelling downward, nearest first (descending order). */
    private final NavigableSet<Integer> downStops = new ConcurrentSkipListSet<>(Comparator.reverseOrder());

    private final AtomicInteger occupancy = new AtomicInteger(0);

    private volatile int currentFloor;
    private volatile ElevatorState state = IdleState.INSTANCE;
    private volatile int consecutiveMovementTicks = 0;

    public Elevator(int id, int minFloor, int maxFloor, int capacity, ObstructionSensor sensor) {
        if (minFloor >= maxFloor) {
            throw new IllegalArgumentException("minFloor must be < maxFloor");
        }
        this.id = id;
        this.minFloor = minFloor;
        this.maxFloor = maxFloor;
        this.capacity = capacity;
        this.currentFloor = minFloor;
        this.door = new Door(sensor);
    }

    /**
     * Registers a floor that this elevator must stop at. Used both for hall
     * calls assigned to this car by the scheduler and for cabin button
     * presses made by passengers already inside it.
     *
     * <p>Thread-safe: may be called concurrently from any number of threads
     * (see class javadoc). If the elevator is already parked at
     * {@code floor}, the door is (re)opened immediately rather than queued,
     * since there is nothing to travel toward.
     */
    public void addStop(int floor) {
        if (floor < minFloor || floor > maxFloor) {
            throw new IllegalArgumentException(
                    "floor " + floor + " outside car range [" + minFloor + "," + maxFloor + "]");
        }
        if (floor == currentFloor) {
            requestImmediateDoorOpen();
        } else if (floor > currentFloor) {
            upStops.add(floor);
        } else {
            downStops.add(floor);
        }
    }

    /** Package-visible hook used by {@code DoorOpenState}/{@code Moving*State} to force a stop at the current floor. */
    public void requestImmediateDoorOpen() {
        synchronized (this) {
            if (!(state instanceof DoorOpenState)) {
                state = DoorOpenState.INSTANCE;
                door.beginOpening();
            }
        }
    }

    /**
     * Advances this elevator by exactly one discrete time unit, delegating to
     * the current {@link ElevatorState}. Synchronized as a defensive
     * single-writer guard (see class javadoc); contention is expected to be
     * zero in normal operation since only one simulation clock drives it.
     */
    public synchronized void tick() {
        Direction before = state.direction();
        ElevatorState next = state.step(this);
        boolean stillSweepingSameDirection = next.direction() == before
                && (before == Direction.UP || before == Direction.DOWN);
        consecutiveMovementTicks = stillSweepingSameDirection ? consecutiveMovementTicks + 1 : 0;
        state = next;
    }

    /**
     * @return how many consecutive ticks this car has spent moving in its
     *         current direction without reversing. Used by
     *         {@link lld.elevatorsystem.scheduler.NearestCarScanStrategy} as
     *         a starvation guard: once this exceeds a fairness threshold the
     *         scheduler stops piling more same-direction work onto this car,
     *         allowing its opposite-direction queue to get a turn.
     */
    public int consecutiveMovementTicks() {
        return consecutiveMovementTicks;
    }

    /** Transitions this car out of service. Pending stops are preserved so service can resume cleanly. */
    public synchronized void enterMaintenance() {
        state = MaintenanceState.INSTANCE;
    }

    /** Returns this car to service; it will resume serving any stops queued before/while under maintenance. */
    public synchronized void exitMaintenance() {
        state = IdleState.INSTANCE;
    }

    public boolean isInMaintenance() {
        return state instanceof MaintenanceState;
    }

    public boolean boardPassenger() {
        return occupancy.getAndUpdate(n -> n < capacity ? n + 1 : n) < capacity;
    }

    public void unboardPassenger(int count) {
        occupancy.updateAndGet(n -> Math.max(0, n - count));
    }

    public int occupancy() {
        return occupancy.get();
    }

    public int capacity() {
        return capacity;
    }

    public int id() {
        return id;
    }

    public int minFloor() {
        return minFloor;
    }

    public int maxFloor() {
        return maxFloor;
    }

    public int currentFloor() {
        return currentFloor;
    }

    /**
     * Moves the car exactly one floor in {@code direction}, clamped to this
     * car's configured range. Called once per tick by {@code MovingUpState}
     * / {@code MovingDownState}.
     */
    public void advanceFloor(Direction direction) {
        if (direction == Direction.UP) {
            currentFloor = Math.min(currentFloor + 1, maxFloor);
        } else if (direction == Direction.DOWN) {
            currentFloor = Math.max(currentFloor - 1, minFloor);
        }
    }

    public Direction direction() {
        return state.direction();
    }

    public String stateLabel() {
        return state.label();
    }

    public Door door() {
        return door;
    }

    public NavigableSet<Integer> upStops() {
        return upStops;
    }

    public NavigableSet<Integer> downStops() {
        return downStops;
    }

    public Set<Integer> pendingStops() {
        Set<Integer> all = new java.util.TreeSet<>(upStops);
        all.addAll(downStops);
        return Collections.unmodifiableSet(all);
    }

    public boolean hasPendingStops() {
        return !upStops.isEmpty() || !downStops.isEmpty();
    }

    public int pendingStopCount() {
        return upStops.size() + downStops.size();
    }

    @Override
    public String toString() {
        return "Elevator{id=" + id + ", floor=" + currentFloor + ", state=" + state.label()
                + ", up=" + upStops + ", down=" + downStops + '}';
    }
}
