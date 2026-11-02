package lld.parkinglot.service;

import lld.parkinglot.enums.SpotSize;
import lld.parkinglot.enums.SpotStatus;
import lld.parkinglot.exception.InvalidSpotOperationException;
import lld.parkinglot.model.Floor;
import lld.parkinglot.model.ParkingSpot;
import lld.parkinglot.repository.InMemorySpotRepository;
import lld.parkinglot.repository.SpotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminServiceTest {

    private SpotRepository spotRepository;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        Floor floor0 = new Floor(0, List.of(
                new ParkingSpot("F0-S1", 0, 1, SpotSize.MEDIUM),
                new ParkingSpot("F0-S2", 0, 2, SpotSize.MEDIUM)));
        spotRepository = new InMemorySpotRepository(List.of(floor0));
        adminService = new AdminService(spotRepository);
    }

    @Test
    void markingASpotOutOfServiceRemovesItFromOccupancyAvailableCount() {
        adminService.markSpotOutOfService("F0-S1");

        assertEquals(1L, adminService.occupancySnapshot().get(SpotStatus.AVAILABLE));
        assertEquals(1L, adminService.occupancySnapshot().get(SpotStatus.OUT_OF_SERVICE));
    }

    @Test
    void bringingASpotBackInServiceRestoresAvailableCount() {
        adminService.markSpotOutOfService("F0-S1");
        adminService.bringSpotBackInService("F0-S1");

        assertEquals(2L, adminService.occupancySnapshot().get(SpotStatus.AVAILABLE));
        assertEquals(0L, adminService.occupancySnapshot().get(SpotStatus.OUT_OF_SERVICE));
    }

    @Test
    void cannotTakeAnOccupiedSpotOutOfService() {
        ParkingSpot spot = spotRepository.reserveSpot(0, SpotSize.MEDIUM).orElseThrow();
        spot.occupy(new lld.parkinglot.model.Vehicle("KA-1", lld.parkinglot.enums.VehicleType.CAR));

        assertThrows(InvalidSpotOperationException.class, () -> adminService.markSpotOutOfService(spot.getId()));
    }

    @Test
    void unknownSpotIdIsRejected() {
        assertThrows(InvalidSpotOperationException.class, () -> adminService.markSpotOutOfService("NOPE"));
    }

    @Test
    void cannotBringBackASpotThatIsNotOutOfService() {
        assertThrows(InvalidSpotOperationException.class, () -> adminService.bringSpotBackInService("F0-S1"));
    }
}
