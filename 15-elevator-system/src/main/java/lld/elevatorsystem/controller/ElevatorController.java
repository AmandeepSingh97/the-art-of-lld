package lld.elevatorsystem.controller;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.elevator.Elevator;
import lld.elevatorsystem.exception.ElevatorOutOfServiceException;
import lld.elevatorsystem.exception.InvalidFloorException;
import lld.elevatorsystem.request.HallRequest;
import lld.elevatorsystem.scheduler.SchedulingStrategy;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The building-wide controller: the single entry point external callers use
 * to raise hall calls and cabin requests, and to advance the simulation
 * clock. It owns the fixed fleet of {@link Elevator}s and the pluggable
 * {@link SchedulingStrategy} (Strategy pattern) used to dispatch hall calls.
 *
 * <h2>Concurrency</h2>
 * <ul>
 *   <li>The elevator fleet is fixed at construction time (no dynamic
 *       add/remove of cars at runtime, a deliberate scope simplification —
 *       see README §1), so iterating {@link #elevators} needs no
 *       synchronization of its own; the list reference itself is never
 *       mutated.</li>
 *   <li>{@link #requestHallCall} may be called concurrently by any number of
 *       threads (one per "floor button panel"). Each call independently
 *       (a) asks the current {@link SchedulingStrategy} to read live elevator
 *       state and choose a car, then (b) inserts the floor into that car's
 *       concurrent stop queue. Step (a) is a read-only snapshot computation
 *       and step (b) is a thread-safe insert into a
 *       {@link java.util.concurrent.ConcurrentSkipListSet}; no lock spans
 *       both steps. The one observable consequence is a benign race: two
 *       hall calls arriving in the same instant might both compute the same
 *       "best" car and both be routed to it (instead of one being routed to
 *       the second-best car). This is a throughput-suboptimal outcome, never
 *       a correctness one — both calls are still served. Fully eliminating it
 *       would require a global dispatch lock serializing all hall calls
 *       across the whole building, which would make every landing panel in
 *       the building contend on one lock; given the benign nature of the
 *       race, that trade-off is not worth making (see
 *       STAFF_LEVEL_DEEP_DIVE.md).</li>
 *   <li>{@link #tick()} is expected to be invoked by exactly one simulation
 *       clock thread; it fans out to each elevator's own
 *       {@link Elevator#tick()}, which independently guards itself (see
 *       {@link Elevator} javadoc).</li>
 * </ul>
 */
public final class ElevatorController {

    private final List<Elevator> elevators;
    private final Map<Integer, Elevator> byId;
    private final int buildingMinFloor;
    private final int buildingMaxFloor;
    private final AtomicLong tickCounter = new AtomicLong(0);

    private volatile SchedulingStrategy strategy;

    public ElevatorController(List<Elevator> elevators, SchedulingStrategy strategy) {
        if (elevators.isEmpty()) {
            throw new IllegalArgumentException("A building needs at least one elevator");
        }
        this.elevators = List.copyOf(elevators);
        this.byId = this.elevators.stream().collect(Collectors.toMap(Elevator::id, Function.identity()));
        this.buildingMinFloor = this.elevators.stream().mapToInt(Elevator::minFloor).min().orElseThrow();
        this.buildingMaxFloor = this.elevators.stream().mapToInt(Elevator::maxFloor).max().orElseThrow();
        this.strategy = strategy;
    }

    /** Swaps the dispatch algorithm at runtime (Strategy pattern) — e.g. to compare naive FCFS vs. nearest-car LOOK. */
    public void setStrategy(SchedulingStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Raises an external hall call: a passenger at {@code floor} wants to
     * travel {@code direction}. Thread-safe; see class javadoc.
     */
    public Elevator requestHallCall(int floor, Direction direction) {
        validateFloor(floor);
        if (direction == Direction.IDLE) {
            throw new InvalidFloorException("Hall call direction must be UP or DOWN");
        }
        HallRequest request = new HallRequest(floor, direction, tickCounter.get());
        Elevator chosen = strategy.selectElevator(request, elevators, tickCounter.get());
        chosen.addStop(floor);
        return chosen;
    }

    /**
     * Raises an internal cabin request inside a specific elevator that is
     * already in service.
     */
    public void requestCabinCall(int elevatorId, int destinationFloor) {
        Elevator elevator = getElevator(elevatorId);
        if (elevator.isInMaintenance()) {
            throw new ElevatorOutOfServiceException("Elevator " + elevatorId + " is in maintenance");
        }
        if (destinationFloor < elevator.minFloor() || destinationFloor > elevator.maxFloor()) {
            throw new InvalidFloorException(
                    "Floor " + destinationFloor + " is outside elevator " + elevatorId + "'s range");
        }
        elevator.addStop(destinationFloor);
    }

    /** Advances every elevator in the fleet by one discrete simulation tick. */
    public void tick() {
        tickCounter.incrementAndGet();
        for (Elevator elevator : elevators) {
            elevator.tick();
        }
    }

    public long currentTick() {
        return tickCounter.get();
    }

    public Elevator getElevator(int elevatorId) {
        Elevator elevator = byId.get(elevatorId);
        if (elevator == null) {
            throw new NoSuchElementException("No elevator with id " + elevatorId);
        }
        return elevator;
    }

    public List<Elevator> elevators() {
        return elevators;
    }

    public List<ElevatorSnapshot> snapshot() {
        return elevators.stream().map(ElevatorSnapshot::of).toList();
    }

    private void validateFloor(int floor) {
        if (floor < buildingMinFloor || floor > buildingMaxFloor) {
            throw new InvalidFloorException(
                    "Floor " + floor + " outside building range [" + buildingMinFloor + "," + buildingMaxFloor + "]");
        }
    }
}
