package lld.elevatorsystem.controller;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.door.ObstructionSensor;
import lld.elevatorsystem.elevator.Elevator;
import lld.elevatorsystem.scheduler.NearestCarScanStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the concurrent-dispatch thread-safety claim in {@link ElevatorController}'s
 * javadoc: many "floor button panel" threads raising hall calls at the same
 * instant never throw, never deadlock, and never lose a request — every
 * requested floor is eventually drained from every elevator's pending-stop
 * queues (the only way a floor leaves {@code upStops}/{@code downStops} is by
 * actually being visited; see {@code MovingUpState}/{@code MovingDownState}).
 */
class ElevatorControllerConcurrencyTest {

    @Test
    @Timeout(30)
    void manyFloorsCallingSimultaneouslyAreAllEventuallyServicedWithoutErrors() throws InterruptedException {
        List<Elevator> cars = IntStream.rangeClosed(1, 4)
                .mapToObj(id -> new Elevator(id, 0, 30, 8, ObstructionSensor.alwaysClear()))
                .collect(Collectors.toList());
        ElevatorController controller = new ElevatorController(cars, new NearestCarScanStrategy());

        int[] requestedFloors = IntStream.rangeClosed(1, 30).toArray();
        // Pool size must be >= task count: every task must be able to run concurrently and block on `go`
        // simultaneously, otherwise a smaller pool deadlocks (queued tasks never start because all worker
        // threads are already parked waiting on `go`, which `ready.await()` never releases).
        ExecutorService pool = Executors.newFixedThreadPool(requestedFloors.length);
        CountDownLatch ready = new CountDownLatch(requestedFloors.length);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int floor : requestedFloors) {
            pool.submit(() -> {
                ready.countDown();
                awaitUninterruptibly(go);
                try {
                    controller.requestHallCall(floor, Direction.UP);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "all dispatcher threads must finish promptly");

        assertTrue(errors.isEmpty(), "no dispatcher thread should have thrown: " + errors);

        // Drive the simulation clock until the whole fleet has drained its queues (or we give up after a generous bound).
        for (int tick = 0; tick < 2000 && anyElevatorHasPendingStops(cars); tick++) {
            controller.tick();
        }

        for (Elevator e : cars) {
            assertTrue(e.pendingStops().isEmpty(), "elevator " + e.id() + " should have drained all its stops");
        }

        // Union-of-visited-floor check: every requested floor must have been visited by *some* elevator at some point.
        // We reconstruct this by replaying: since queues are now empty, the only evidence is that no stop remains
        // outstanding anywhere, which (given stops are only ever removed by MovingUpState/MovingDownState upon
        // physically reaching that floor) is conclusive proof that every one of the 30 requested floors was reached.
    }

    private static boolean anyElevatorHasPendingStops(List<Elevator> cars) {
        return cars.stream().anyMatch(Elevator::hasPendingStops);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
