package lld.parkinglot.repository;

import lld.parkinglot.enums.SpotSize;
import lld.parkinglot.model.Floor;
import lld.parkinglot.model.ParkingSpot;

import java.util.List;
import java.util.Optional;

/**
 * Owns the physical layout (floors/spots) and the concurrency-critical
 * allocation indices derived from it. This is the seam between the pure
 * domain model and "where state actually lives"; an alternative
 * implementation (e.g. backed by a database + row-level locking, see
 * {@code README.md} section 12) only needs to satisfy this interface.
 */
public interface SpotRepository {

    /** All floors in the lot, structural/read-mostly. */
    List<Floor> getFloors();

    /**
     * Atomically finds and reserves the lowest-spot-number, currently
     * AVAILABLE spot of {@code size} on {@code floorNumber}, if any.
     * Thread-safe: concurrent callers racing for the same floor/size never
     * receive the same spot.
     */
    Optional<ParkingSpot> reserveSpot(int floorNumber, SpotSize size);

    /** Abandons a reservation that did not result in a parked vehicle, returning the spot to the available pool. */
    void releaseReservation(ParkingSpot spot);

    /** Frees a spot when its vehicle exits, returning it to the available pool. */
    void freeSpot(ParkingSpot spot);

    /** Looks up a spot by id. */
    Optional<ParkingSpot> findById(String spotId);

    /** Admin operation: removes a currently-free spot from the allocatable pool. @return false if the spot was not AVAILABLE. */
    boolean markOutOfService(String spotId);

    /** Admin operation: returns an out-of-service spot to the allocatable pool. @return false if the spot was not OUT_OF_SERVICE. */
    boolean bringBackInService(String spotId);
}
