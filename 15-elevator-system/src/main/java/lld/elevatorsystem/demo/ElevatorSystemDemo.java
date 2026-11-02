package lld.elevatorsystem.demo;

import lld.elevatorsystem.Direction;
import lld.elevatorsystem.controller.ElevatorController;
import lld.elevatorsystem.controller.ElevatorSnapshot;
import lld.elevatorsystem.door.ObstructionSensor;
import lld.elevatorsystem.elevator.Elevator;
import lld.elevatorsystem.scheduler.NaiveFcfsStrategy;
import lld.elevatorsystem.scheduler.NearestCarScanStrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runnable demonstration of the elevator system: a 10-floor building with 3
 * cars. Shows (1) concurrent hall calls dispatched from multiple floors at
 * once, (2) the SCAN/LOOK sweep servicing cabin + hall requests in floor
 * order, (3) a door obstruction being safely retried, and (4) a maintenance
 * car being skipped by the scheduler.
 */
public final class ElevatorSystemDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Elevator System Demo ===");
        concurrentDispatchDemo();
        System.out.println();
        doorObstructionDemo();
        System.out.println();
        maintenanceDemo();
        System.out.println();
        strategyComparisonDemo();
    }

    private static void concurrentDispatchDemo() throws InterruptedException {
        System.out.println("-- Concurrent hall calls from multiple floors --");
        List<Elevator> cars = List.of(
                new Elevator(1, 0, 10, 8, ObstructionSensor.alwaysClear()),
                new Elevator(2, 0, 10, 8, ObstructionSensor.alwaysClear()),
                new Elevator(3, 0, 10, 8, ObstructionSensor.alwaysClear()));
        ElevatorController controller = new ElevatorController(cars, new NearestCarScanStrategy());
        // Spread the cars out first so the concurrent hall calls below have a genuine "nearest car" to pick between
        // instead of racing over an artificial tie (three idle cars stacked on the same floor).
        controller.requestCabinCall(1, 0);
        controller.requestCabinCall(2, 5);
        controller.requestCabinCall(3, 10);
        for (int t = 0; t < 8; t++) {
            controller.tick();
        }

        // Five different floors raise a hall call "simultaneously" from five threads.
        int[] callingFloors = {1, 9, 4, 6, 2};
        ExecutorService pool = Executors.newFixedThreadPool(callingFloors.length);
        CountDownLatch ready = new CountDownLatch(callingFloors.length);
        CountDownLatch go = new CountDownLatch(1);
        for (int floor : callingFloors) {
            pool.submit(() -> {
                ready.countDown();
                awaitUninterruptibly(go);
                controller.requestHallCall(floor, Direction.UP);
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        for (int t = 0; t < 15; t++) {
            controller.tick();
        }
        printFleet(controller);
    }

    private static void doorObstructionDemo() {
        System.out.println("-- Door obstruction safety --");
        AtomicInteger checks = new AtomicInteger(0);
        // Obstructed for the first two close attempts, clear afterward.
        ObstructionSensor flaky = () -> checks.incrementAndGet() <= 2;
        List<Elevator> cars = List.of(new Elevator(1, 0, 5, 8, flaky));
        ElevatorController controller = new ElevatorController(cars, new NearestCarScanStrategy());

        controller.requestCabinCall(1, 3);
        for (int t = 0; t < 12; t++) {
            controller.tick();
            ElevatorSnapshot s = controller.snapshot().get(0);
            System.out.printf("tick=%d floor=%d state=%s door=%s%n", t, s.currentFloor(), s.stateLabel(), s.doorState());
        }
    }

    private static void maintenanceDemo() {
        System.out.println("-- Maintenance car is skipped by the scheduler --");
        List<Elevator> cars = List.of(
                new Elevator(1, 0, 10, 8, ObstructionSensor.alwaysClear()),
                new Elevator(2, 0, 10, 8, ObstructionSensor.alwaysClear()));
        cars.get(0).enterMaintenance();
        ElevatorController controller = new ElevatorController(cars, new NearestCarScanStrategy());

        Elevator chosen = controller.requestHallCall(4, Direction.UP);
        System.out.println("Hall call at floor 4 assigned to elevator id=" + chosen.id()
                + " (elevator 1 is under maintenance, so elevator 2 must be chosen)");
    }

    private static void strategyComparisonDemo() {
        System.out.println("-- Naive FCFS vs. Nearest-Car SCAN/LOOK: wait-time comparison --");
        System.out.println("(Car 1 idle at floor 0, car 2 idle at floor 20; 10 hall calls scattered across the building)");
        runWaitExperiment(new NaiveFcfsStrategy(), "NAIVE FCFS");
        runWaitExperiment(new NearestCarScanStrategy(), "NEAREST-CAR SCAN");
    }

    private static void runWaitExperiment(lld.elevatorsystem.scheduler.SchedulingStrategy strategy, String label) {
        List<Elevator> cars = new ArrayList<>();
        cars.add(new Elevator(1, 0, 20, 8, ObstructionSensor.alwaysClear()));
        cars.add(new Elevator(2, 0, 20, 8, ObstructionSensor.alwaysClear()));
        ElevatorController controller = new ElevatorController(cars, strategy);
        // Move car 2 to the opposite end of the building before the wave so both cars have a genuinely
        // different position to be judged on (two cars stacked at the same floor would make every
        // strategy look identical).
        controller.requestCabinCall(2, 20);
        for (int i = 0; i < 25; i++) {
            controller.tick();
        }

        int[] floors = {2, 18, 4, 16, 6, 14, 8, 12, 10, 3};
        record Pending(int elevatorId, int floor, long requestedTick) {
        }
        List<Pending> pendings = new ArrayList<>();
        for (int floor : floors) {
            Elevator chosen = controller.requestHallCall(floor, Direction.DOWN);
            pendings.add(new Pending(chosen.id(), floor, controller.currentTick()));
        }

        Map<Pending, Integer> waitTicks = new LinkedHashMap<>();
        for (Pending p : pendings) {
            waitTicks.put(p, -1);
        }
        for (int t = 0; t < 400 && waitTicks.containsValue(-1); t++) {
            controller.tick();
            for (Pending p : pendings) {
                if (waitTicks.get(p) == -1 && controller.getElevator(p.elevatorId()).currentFloor() == p.floor()) {
                    waitTicks.put(p, t + 1);
                }
            }
        }

        int total = 0;
        for (var entry : waitTicks.entrySet()) {
            total += entry.getValue();
        }
        double average = total / (double) floors.length;
        System.out.printf("%-18s average wait = %.1f ticks (floor 3, requested last, waited %d ticks)%n",
                label, average, waitTicks.entrySet().stream()
                        .filter(e -> e.getKey().floor() == 3).findFirst().map(Map.Entry::getValue).orElse(-1));
    }

    private static void printFleet(ElevatorController controller) {
        for (ElevatorSnapshot s : controller.snapshot()) {
            System.out.printf("elevator id=%d floor=%d dir=%s state=%s pending=%s%n",
                    s.id(), s.currentFloor(), s.direction(), s.stateLabel(), s.pendingStops());
        }
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
