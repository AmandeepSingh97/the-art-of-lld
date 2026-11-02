package lld.parkinglot.strategy;

import lld.parkinglot.enums.SpotSize;
import lld.parkinglot.enums.VehicleType;
import lld.parkinglot.model.Floor;
import lld.parkinglot.model.Gate;
import lld.parkinglot.model.ParkingSpot;
import lld.parkinglot.model.Vehicle;
import lld.parkinglot.repository.InMemorySpotRepository;
import lld.parkinglot.repository.SpotRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearestAvailableSpotAllocationStrategyTest {

    private SpotRepository threeFloorRepository() {
        Floor floor0 = new Floor(0, List.of(
                new ParkingSpot("F0-S1", 0, 1, SpotSize.SMALL),
                new ParkingSpot("F0-S2", 0, 2, SpotSize.MEDIUM)));
        Floor floor1 = new Floor(1, List.of(
                new ParkingSpot("F1-S1", 1, 1, SpotSize.MEDIUM)));
        Floor floor2 = new Floor(2, List.of(
                new ParkingSpot("F2-S1", 2, 1, SpotSize.MEDIUM),
                new ParkingSpot("F2-S2", 2, 2, SpotSize.LARGE)));
        return new InMemorySpotRepository(List.of(floor0, floor1, floor2));
    }

    @Test
    void prefersSmallestSufficientSpotOverLargerOnSameFloor() {
        SpotRepository repository = threeFloorRepository();
        SpotAllocationStrategy strategy = new NearestAvailableSpotAllocationStrategy(repository);
        Gate groundEntry = new Gate("G0", "Ground", 0, Gate.Kind.ENTRY);

        Optional<ParkingSpot> reserved = strategy.reserveSpotFor(new Vehicle("KA-1", VehicleType.MOTORCYCLE), groundEntry);

        assertTrue(reserved.isPresent());
        assertEquals("F0-S1", reserved.get().getId());
        assertEquals(SpotSize.SMALL, reserved.get().getSize());
    }

    @Test
    void prefersTheFloorClosestToTheEntryGateUsed() {
        SpotRepository repository = threeFloorRepository();
        SpotAllocationStrategy strategy = new NearestAvailableSpotAllocationStrategy(repository);
        // Entry gate physically on floor 2: nearest compatible (MEDIUM/LARGE) spot for a car should be on floor 2.
        Gate topEntry = new Gate("G2", "Top", 2, Gate.Kind.ENTRY);

        Optional<ParkingSpot> reserved = strategy.reserveSpotFor(new Vehicle("KA-2", VehicleType.CAR), topEntry);

        assertTrue(reserved.isPresent());
        assertEquals(2, reserved.get().getFloorNumber());
    }

    @Test
    void busCanOnlyUseLargeSpots() {
        SpotRepository repository = threeFloorRepository();
        SpotAllocationStrategy strategy = new NearestAvailableSpotAllocationStrategy(repository);
        Gate groundEntry = new Gate("G0", "Ground", 0, Gate.Kind.ENTRY);

        Optional<ParkingSpot> reserved = strategy.reserveSpotFor(new Vehicle("KA-3", VehicleType.BUS_OR_TRUCK), groundEntry);

        assertTrue(reserved.isPresent());
        assertEquals("F2-S2", reserved.get().getId());
        assertEquals(SpotSize.LARGE, reserved.get().getSize());
    }

    @Test
    void returnsEmptyWhenNoCompatibleSpotExistsAnywhere() {
        Floor floor0 = new Floor(0, List.of(new ParkingSpot("F0-S1", 0, 1, SpotSize.SMALL)));
        SpotRepository repository = new InMemorySpotRepository(List.of(floor0));
        SpotAllocationStrategy strategy = new NearestAvailableSpotAllocationStrategy(repository);
        Gate groundEntry = new Gate("G0", "Ground", 0, Gate.Kind.ENTRY);

        Optional<ParkingSpot> reserved = strategy.reserveSpotFor(new Vehicle("KA-4", VehicleType.BUS_OR_TRUCK), groundEntry);

        assertTrue(reserved.isEmpty());
    }
}
