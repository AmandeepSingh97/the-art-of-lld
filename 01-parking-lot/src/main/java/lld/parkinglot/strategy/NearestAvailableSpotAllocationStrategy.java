package lld.parkinglot.strategy;

import lld.parkinglot.enums.SpotSize;
import lld.parkinglot.model.Floor;
import lld.parkinglot.model.Gate;
import lld.parkinglot.model.ParkingSpot;
import lld.parkinglot.model.Vehicle;
import lld.parkinglot.repository.SpotRepository;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Default allocation policy: among all compatible, in-service spots, prefer
 * (1) the floor physically closest to the entry gate used, and on that floor
 * (2) the smallest spot size that can still legally hold the vehicle - so a
 * motorcycle does not waste a large (bus-capable) spot, and (3) the lowest
 * spot number on that floor (assumed closest to the stairwell/elevator).
 *
 * <p>All of the actual atomicity lives in {@link SpotRepository#reserveSpot};
 * this class only computes <em>candidate order</em>, which is pure,
 * side-effect-free and therefore trivially thread-safe to call concurrently
 * from multiple entry gates.
 */
public final class NearestAvailableSpotAllocationStrategy implements SpotAllocationStrategy {

    private final SpotRepository repository;

    public NearestAvailableSpotAllocationStrategy(SpotRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ParkingSpot> reserveSpotFor(Vehicle vehicle, Gate entryGate) {
        List<Floor> floorsByProximity = repository.getFloors().stream()
                .sorted(Comparator
                        .comparingInt((Floor f) -> Math.abs(f.getFloorNumber() - entryGate.getFloorNumber()))
                        .thenComparingInt(Floor::getFloorNumber))
                .collect(Collectors.toList());

        List<SpotSize> candidateSizesSmallestFirst = Arrays.stream(SpotSize.values())
                .filter(size -> size.canFit(vehicle.getType()))
                .collect(Collectors.toList());

        for (Floor floor : floorsByProximity) {
            for (SpotSize size : candidateSizesSmallestFirst) {
                Optional<ParkingSpot> reserved = repository.reserveSpot(floor.getFloorNumber(), size);
                if (reserved.isPresent()) {
                    return reserved;
                }
            }
        }
        return Optional.empty();
    }
}
