package lld.parkinglot.model;

import lld.parkinglot.enums.SpotSize;
import lld.parkinglot.enums.SpotStatus;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A single physical parking spot. This is the unit of concurrency control in
 * the whole system: every allocation, occupy, vacate and admin
 * in/out-of-service operation ultimately boils down to one lock-free
 * compare-and-set on {@link #status}.
 *
 * <h2>State machine</h2>
 * <pre>
 *   AVAILABLE --tryReserve()--------&gt; RESERVED
 *   RESERVED  --occupy(vehicle)-----&gt; OCCUPIED
 *   RESERVED  --releaseReservation()-&gt; AVAILABLE   (allocation abandoned, e.g. ticket creation failed)
 *   OCCUPIED  --vacate()------------&gt; AVAILABLE
 *   AVAILABLE --tryMarkOutOfService()-&gt; OUT_OF_SERVICE
 *   OUT_OF_SERVICE --bringBackInService()-&gt; AVAILABLE
 * </pre>
 *
 * <h2>Thread-safety</h2>
 * {@link #status} is an {@link AtomicReference} and every transition method
 * below is implemented as a single {@code compareAndSet}. This gives
 * linearizable, lock-free transitions: two threads racing to reserve, or one
 * thread racing an admin "take out of service" against another reserving,
 * can never both "win" the same spot. {@link #currentVehicle} is only ever
 * written by the thread that just won the {@code RESERVED -&gt; OCCUPIED} (or
 * symmetric) CAS, and is read by the unparking thread which first must have
 * discovered this spot via the ticket (itself only handed out after the
 * state transition completed) - so there is a happens-before edge through the
 * {@code AtomicReference} write/read and no additional synchronization is
 * required for {@link #currentVehicle} despite it not being {@code volatile}
 * on its own; it is nonetheless declared {@code volatile} defensively so this
 * class remains safe even if a future change reads it from an unrelated thread.
 */
public final class ParkingSpot {

    private final String id;
    private final int floorNumber;
    private final int spotNumber;
    private final SpotSize size;
    private final AtomicReference<SpotStatus> status = new AtomicReference<>(SpotStatus.AVAILABLE);
    private volatile Vehicle currentVehicle;

    public ParkingSpot(String id, int floorNumber, int spotNumber, SpotSize size) {
        this.id = Objects.requireNonNull(id, "id");
        this.floorNumber = floorNumber;
        this.spotNumber = spotNumber;
        this.size = Objects.requireNonNull(size, "size");
    }

    public String getId() {
        return id;
    }

    public int getFloorNumber() {
        return floorNumber;
    }

    public int getSpotNumber() {
        return spotNumber;
    }

    public SpotSize getSize() {
        return size;
    }

    public SpotStatus getStatus() {
        return status.get();
    }

    public Vehicle getCurrentVehicle() {
        return currentVehicle;
    }

    /** Atomically claims this spot for allocation. Returns false if another thread won the race, or it is not available. */
    public boolean tryReserve() {
        return status.compareAndSet(SpotStatus.AVAILABLE, SpotStatus.RESERVED);
    }

    /** Completes an allocation by attaching the vehicle and moving RESERVED -&gt; OCCUPIED. */
    public void occupy(Vehicle vehicle) {
        Objects.requireNonNull(vehicle, "vehicle");
        if (!status.compareAndSet(SpotStatus.RESERVED, SpotStatus.OCCUPIED)) {
            throw new IllegalStateException("Spot " + id + " cannot be occupied from state " + status.get());
        }
        this.currentVehicle = vehicle;
    }

    /** Abandons a reservation that did not result in a parked vehicle (e.g. ticket persistence failed). */
    public void releaseReservation() {
        if (!status.compareAndSet(SpotStatus.RESERVED, SpotStatus.AVAILABLE)) {
            throw new IllegalStateException("Spot " + id + " cannot release reservation from state " + status.get());
        }
    }

    /** Frees the spot when a vehicle exits. */
    public void vacate() {
        if (!status.compareAndSet(SpotStatus.OCCUPIED, SpotStatus.AVAILABLE)) {
            throw new IllegalStateException("Spot " + id + " cannot be vacated from state " + status.get());
        }
        this.currentVehicle = null;
    }

    /** Admin operation: remove a currently free spot from the allocatable pool. Fails if occupied/reserved. */
    public boolean tryMarkOutOfService() {
        return status.compareAndSet(SpotStatus.AVAILABLE, SpotStatus.OUT_OF_SERVICE);
    }

    /** Admin operation: return a spot to the allocatable pool. */
    public boolean bringBackInService() {
        return status.compareAndSet(SpotStatus.OUT_OF_SERVICE, SpotStatus.AVAILABLE);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ParkingSpot)) return false;
        ParkingSpot that = (ParkingSpot) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Spot[" + id + ", floor=" + floorNumber + ", size=" + size + ", status=" + status.get() + "]";
    }
}
