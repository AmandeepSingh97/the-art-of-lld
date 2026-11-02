package lld.parkinglot.strategy;

import lld.parkinglot.model.Gate;
import lld.parkinglot.model.ParkingSpot;
import lld.parkinglot.model.Vehicle;

import java.util.Optional;

/**
 * Strategy Pattern: decides WHICH spot a vehicle gets. Kept separate from
 * {@code SpotRepository} (which owns WHERE the spots and indices live) so
 * that the allocation policy - nearest-available today, "prefer ground
 * floor for accessibility permits" or "load-balance across floors" tomorrow
 * - can be swapped without touching the repository or the service layer.
 *
 * <p>Implementations must be thread-safe: multiple entry gates call this
 * concurrently.
 */
public interface SpotAllocationStrategy {

    /**
     * Attempts to find and atomically reserve one compatible, in-service
     * spot for {@code vehicle}, entering through {@code entryGate}.
     *
     * @return the reserved spot, or {@link Optional#empty()} if the lot has
     *         no compatible free spot right now. The caller owns the
     *         reservation and must either {@code occupy} or
     *         {@code releaseReservation} it.
     */
    Optional<ParkingSpot> reserveSpotFor(Vehicle vehicle, Gate entryGate);
}
