package lld.parkinglot.service;

import lld.parkinglot.enums.SpotStatus;
import lld.parkinglot.exception.InvalidSpotOperationException;
import lld.parkinglot.model.Floor;
import lld.parkinglot.model.ParkingSpot;
import lld.parkinglot.repository.SpotRepository;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Operational controls for garage staff: taking spots in/out of service
 * (maintenance, damage, cleaning) and reading occupancy. Deliberately a
 * separate class from {@link ParkingLotService}: these are operator
 * actions with different authorization/audit requirements in production,
 * not part of the core park/unpark domain flow, and giving them their own
 * class keeps {@code ParkingLotService} focused (Single Responsibility).
 */
public final class AdminService {

    private final SpotRepository spotRepository;

    public AdminService(SpotRepository spotRepository) {
        this.spotRepository = Objects.requireNonNull(spotRepository, "spotRepository");
    }

    /**
     * Takes a spot out of service, e.g. for maintenance.
     *
     * @throws InvalidSpotOperationException if the spot id is unknown, or the spot is
     *         currently occupied/reserved (cannot be pulled from under a parked vehicle)
     *         or already out of service
     */
    public void markSpotOutOfService(String spotId) {
        ParkingSpot spot = spotRepository.findById(spotId)
                .orElseThrow(() -> new InvalidSpotOperationException("Unknown spot id " + spotId));
        if (!spotRepository.markOutOfService(spotId)) {
            throw new InvalidSpotOperationException(
                    "Spot " + spotId + " cannot be taken out of service from state " + spot.getStatus());
        }
    }

    /**
     * Restores a spot to service.
     *
     * @throws InvalidSpotOperationException if the spot id is unknown or the spot was not OUT_OF_SERVICE
     */
    public void bringSpotBackInService(String spotId) {
        spotRepository.findById(spotId)
                .orElseThrow(() -> new InvalidSpotOperationException("Unknown spot id " + spotId));
        if (!spotRepository.bringBackInService(spotId)) {
            throw new InvalidSpotOperationException("Spot " + spotId + " was not OUT_OF_SERVICE");
        }
    }

    /** @return a snapshot count of spots per status, for dashboards/health checks. */
    public Map<SpotStatus, Long> occupancySnapshot() {
        Map<SpotStatus, Long> counts = new EnumMap<>(SpotStatus.class);
        for (SpotStatus status : SpotStatus.values()) {
            counts.put(status, 0L);
        }
        for (Floor floor : spotRepository.getFloors()) {
            for (ParkingSpot spot : floor.getSpots()) {
                counts.merge(spot.getStatus(), 1L, Long::sum);
            }
        }
        return counts;
    }
}
