package lld.parkinglot.model;

import lld.parkinglot.enums.SpotSize;
import lld.parkinglot.enums.SpotStatus;
import lld.parkinglot.enums.VehicleType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkingSpotTest {

    @Test
    void newSpotStartsAvailableWithNoVehicle() {
        ParkingSpot spot = new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM);
        assertEquals(SpotStatus.AVAILABLE, spot.getStatus());
        assertEquals(null, spot.getCurrentVehicle());
    }

    @Test
    void fullLifecycleTransitionsCorrectly() {
        ParkingSpot spot = new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM);
        Vehicle vehicle = new Vehicle("KA-01-AA-0001", VehicleType.CAR);

        assertTrue(spot.tryReserve());
        assertEquals(SpotStatus.RESERVED, spot.getStatus());

        spot.occupy(vehicle);
        assertEquals(SpotStatus.OCCUPIED, spot.getStatus());
        assertEquals(vehicle, spot.getCurrentVehicle());

        spot.vacate();
        assertEquals(SpotStatus.AVAILABLE, spot.getStatus());
        assertEquals(null, spot.getCurrentVehicle());
    }

    @Test
    void cannotReserveAnAlreadyReservedSpot() {
        ParkingSpot spot = new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM);
        assertTrue(spot.tryReserve());
        assertFalse(spot.tryReserve());
    }

    @Test
    void cannotOccupyASpotThatIsNotReserved() {
        ParkingSpot spot = new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM);
        Vehicle vehicle = new Vehicle("KA-01-AA-0001", VehicleType.CAR);
        assertThrows(IllegalStateException.class, () -> spot.occupy(vehicle));
    }

    @Test
    void cannotVacateASpotThatIsNotOccupied() {
        ParkingSpot spot = new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM);
        assertThrows(IllegalStateException.class, spot::vacate);
    }

    @Test
    void releaseReservationReturnsSpotToAvailable() {
        ParkingSpot spot = new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM);
        assertTrue(spot.tryReserve());
        spot.releaseReservation();
        assertEquals(SpotStatus.AVAILABLE, spot.getStatus());
        assertTrue(spot.tryReserve());
    }

    @Test
    void outOfServiceCycleRoundTrips() {
        ParkingSpot spot = new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM);
        assertTrue(spot.tryMarkOutOfService());
        assertEquals(SpotStatus.OUT_OF_SERVICE, spot.getStatus());
        assertFalse(spot.tryReserve());
        assertTrue(spot.bringBackInService());
        assertEquals(SpotStatus.AVAILABLE, spot.getStatus());
    }

    @Test
    void cannotTakeAnOccupiedSpotOutOfService() {
        ParkingSpot spot = new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM);
        assertTrue(spot.tryReserve());
        spot.occupy(new Vehicle("KA-01-AA-0001", VehicleType.CAR));
        assertFalse(spot.tryMarkOutOfService());
        assertEquals(SpotStatus.OCCUPIED, spot.getStatus());
    }

    /**
     * The crux concurrency guarantee of the whole system: when many threads
     * race to reserve the same single spot, exactly one must win. This is a
     * negative-control-worthy test - a naive "if (status == AVAILABLE) status
     * = RESERVED" (non-atomic check-then-act) implementation would let
     * multiple threads win under real contention, which is exactly the bug
     * {@link java.util.concurrent.atomic.AtomicReference#compareAndSet} rules out.
     */
    @Test
    void exactlyOneThreadWinsConcurrentReservation() throws InterruptedException {
        ParkingSpot spot = new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM);
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (spot.tryReserve()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(1, successCount.get());
        assertEquals(SpotStatus.RESERVED, spot.getStatus());
    }
}
