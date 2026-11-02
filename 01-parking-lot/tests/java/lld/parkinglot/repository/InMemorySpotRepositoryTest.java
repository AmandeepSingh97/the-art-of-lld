package lld.parkinglot.repository;

import lld.parkinglot.enums.SpotSize;
import lld.parkinglot.model.Floor;
import lld.parkinglot.model.ParkingSpot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemorySpotRepositoryTest {

    @Test
    void reserveSpotReturnsLowestNumberedAvailableSpotFirst() {
        Floor floor = new Floor(0, List.of(
                new ParkingSpot("S3", 0, 3, SpotSize.MEDIUM),
                new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM),
                new ParkingSpot("S2", 0, 2, SpotSize.MEDIUM)));
        InMemorySpotRepository repository = new InMemorySpotRepository(List.of(floor));

        Optional<ParkingSpot> first = repository.reserveSpot(0, SpotSize.MEDIUM);

        assertTrue(first.isPresent());
        assertEquals("S1", first.get().getId());
    }

    @Test
    void reserveSpotReturnsEmptyWhenBucketExhausted() {
        Floor floor = new Floor(0, List.of(new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM)));
        InMemorySpotRepository repository = new InMemorySpotRepository(List.of(floor));

        assertTrue(repository.reserveSpot(0, SpotSize.MEDIUM).isPresent());
        assertTrue(repository.reserveSpot(0, SpotSize.MEDIUM).isEmpty());
    }

    @Test
    void freeSpotMakesItReservableAgain() {
        Floor floor = new Floor(0, List.of(new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM)));
        InMemorySpotRepository repository = new InMemorySpotRepository(List.of(floor));

        ParkingSpot spot = repository.reserveSpot(0, SpotSize.MEDIUM).orElseThrow();
        spot.occupy(new lld.parkinglot.model.Vehicle("KA-1", lld.parkinglot.enums.VehicleType.CAR));
        repository.freeSpot(spot);

        assertTrue(repository.reserveSpot(0, SpotSize.MEDIUM).isPresent());
    }

    @Test
    void markOutOfServiceRemovesSpotFromAllocationPool() {
        Floor floor = new Floor(0, List.of(new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM)));
        InMemorySpotRepository repository = new InMemorySpotRepository(List.of(floor));

        assertTrue(repository.markOutOfService("S1"));
        assertTrue(repository.reserveSpot(0, SpotSize.MEDIUM).isEmpty());
    }

    @Test
    void markOutOfServiceFailsIfSpotIsAlreadyReserved() {
        Floor floor = new Floor(0, List.of(new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM)));
        InMemorySpotRepository repository = new InMemorySpotRepository(List.of(floor));
        repository.reserveSpot(0, SpotSize.MEDIUM);

        assertFalse(repository.markOutOfService("S1"));
    }

    @Test
    void bringBackInServiceMakesSpotAvailableAgain() {
        Floor floor = new Floor(0, List.of(new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM)));
        InMemorySpotRepository repository = new InMemorySpotRepository(List.of(floor));
        repository.markOutOfService("S1");

        assertTrue(repository.bringBackInService("S1"));
        assertTrue(repository.reserveSpot(0, SpotSize.MEDIUM).isPresent());
    }

    /**
     * Negative-control-style concurrency test: 100 threads all call
     * {@code reserveSpot} against a 30-spot bucket. A buggy implementation
     * that read-then-removed non-atomically (e.g. {@code first()} followed
     * by a separate {@code remove()}) could hand the same spot to two
     * threads under contention; this test would then fail the uniqueness
     * assertion. With the real pollFirst()+CAS implementation it must not.
     */
    @Test
    void concurrentReservationsAcrossManyThreadsNeverDoubleAllocateASpot() throws InterruptedException {
        int spotCount = 30;
        int threadCount = 100;
        List<ParkingSpot> spots = IntStream.rangeClosed(1, spotCount)
                .mapToObj(i -> new ParkingSpot("S" + i, 0, i, SpotSize.MEDIUM))
                .collect(Collectors.toList());
        Floor floor = new Floor(0, spots);
        InMemorySpotRepository repository = new InMemorySpotRepository(List.of(floor));

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        Set<String> wonSpotIds = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    repository.reserveSpot(0, SpotSize.MEDIUM).ifPresent(spot -> wonSpotIds.add(spot.getId()));
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

        assertEquals(spotCount, wonSpotIds.size());
    }
}
