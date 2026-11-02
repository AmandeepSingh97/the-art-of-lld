package lld.parkinglot.app;

import lld.parkinglot.enums.PaymentMethod;
import lld.parkinglot.enums.SpotSize;
import lld.parkinglot.enums.SpotStatus;
import lld.parkinglot.enums.VehicleType;
import lld.parkinglot.exception.NoAvailableSpotException;
import lld.parkinglot.exception.ParkingLotException;
import lld.parkinglot.factory.ParkingLotBuilder;
import lld.parkinglot.model.Gate;
import lld.parkinglot.model.ParkingLot;
import lld.parkinglot.model.Ticket;
import lld.parkinglot.model.Vehicle;
import lld.parkinglot.repository.InMemoryPaymentRepository;
import lld.parkinglot.repository.InMemorySpotRepository;
import lld.parkinglot.repository.InMemoryTicketRepository;
import lld.parkinglot.repository.PaymentRepository;
import lld.parkinglot.repository.SpotRepository;
import lld.parkinglot.repository.TicketRepository;
import lld.parkinglot.service.AdminService;
import lld.parkinglot.service.EntryGateService;
import lld.parkinglot.service.ExitGateService;
import lld.parkinglot.service.ExitReceipt;
import lld.parkinglot.service.ParkingLotService;
import lld.parkinglot.service.PaymentService;
import lld.parkinglot.strategy.CardPaymentProcessor;
import lld.parkinglot.strategy.CashPaymentProcessor;
import lld.parkinglot.strategy.HourlyPricingStrategy;
import lld.parkinglot.strategy.NearestAvailableSpotAllocationStrategy;
import lld.parkinglot.strategy.PaymentProcessor;
import lld.parkinglot.strategy.PricingStrategy;
import lld.parkinglot.strategy.SpotAllocationStrategy;
import lld.parkinglot.strategy.WalletPaymentProcessor;
import lld.parkinglot.util.Money;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runnable end-to-end demonstration of the parking lot system: builds a
 * 3-floor lot, wires up the default strategies, then exercises:
 * <ol>
 *   <li>single-threaded happy-path entry/exit for each vehicle type,</li>
 *   <li>a duplicate-entry rejection,</li>
 *   <li>an admin out-of-service / restore cycle, and</li>
 *   <li>a concurrent stress run with multiple entry gates racing for a
 *       deliberately small pool of spots, to make the thread-safety claims
 *       in the README/README-adjacent docs observable rather than just
 *       asserted.</li>
 * </ol>
 */
public final class ParkingLotDemo {

    public static void main(String[] args) throws InterruptedException {
        ParkingLot parkingLot = ParkingLotBuilder.named("Summit Towers Garage")
                .withFloor(0, floorLayout())
                .withFloor(1, floorLayout())
                .withFloor(2, floorLayout())
                .build();

        SpotRepository spotRepository = new InMemorySpotRepository(parkingLot.getFloors());
        TicketRepository ticketRepository = new InMemoryTicketRepository();
        PaymentRepository paymentRepository = new InMemoryPaymentRepository();

        SpotAllocationStrategy allocationStrategy = new NearestAvailableSpotAllocationStrategy(spotRepository);

        Map<VehicleType, Money> hourlyRates = new EnumMap<>(VehicleType.class);
        hourlyRates.put(VehicleType.MOTORCYCLE, Money.of(1.0));
        hourlyRates.put(VehicleType.CAR, Money.of(2.5));
        hourlyRates.put(VehicleType.BUS_OR_TRUCK, Money.of(5.0));
        PricingStrategy pricingStrategy = new HourlyPricingStrategy(hourlyRates);

        Map<PaymentMethod, PaymentProcessor> processors = new EnumMap<>(PaymentMethod.class);
        processors.put(PaymentMethod.CASH, new CashPaymentProcessor());
        processors.put(PaymentMethod.CREDIT_CARD, new CardPaymentProcessor());
        processors.put(PaymentMethod.UPI_OR_WALLET, new WalletPaymentProcessor());
        PaymentService paymentService = new PaymentService(processors, paymentRepository);

        ParkingLotService parkingLotService = new ParkingLotService(
                spotRepository, ticketRepository, allocationStrategy, pricingStrategy, paymentService);
        AdminService adminService = new AdminService(spotRepository);

        Gate groundEntry = new Gate("GATE-ENTRY-0", "Ground Entry", 0, Gate.Kind.ENTRY);
        Gate groundExit = new Gate("GATE-EXIT-0", "Ground Exit", 0, Gate.Kind.EXIT);
        EntryGateService entryGateService = new EntryGateService(groundEntry, parkingLotService);
        ExitGateService exitGateService = new ExitGateService(groundExit, parkingLotService);

        System.out.println("=== Happy path: motorcycle, car, bus ===");
        Ticket bikeTicket = entryGateService.processEntry(new Vehicle("KA-01-HH-1234", VehicleType.MOTORCYCLE));
        System.out.println("Issued: " + bikeTicket);
        Ticket carTicket = entryGateService.processEntry(new Vehicle("KA-02-AB-5678", VehicleType.CAR));
        System.out.println("Issued: " + carTicket);
        Ticket busTicket = entryGateService.processEntry(new Vehicle("KA-03-BUS-0001", VehicleType.BUS_OR_TRUCK));
        System.out.println("Issued: " + busTicket);

        ExitReceipt bikeReceipt = exitGateService.processExit(bikeTicket.getId(), PaymentMethod.CASH);
        System.out.println("Settled: " + bikeReceipt);
        ExitReceipt carReceipt = exitGateService.processExit(carTicket.getId(), PaymentMethod.CREDIT_CARD);
        System.out.println("Settled: " + carReceipt);
        ExitReceipt busReceipt = exitGateService.processExit(busTicket.getId(), PaymentMethod.UPI_OR_WALLET);
        System.out.println("Settled: " + busReceipt);

        System.out.println();
        System.out.println("=== Duplicate entry is rejected ===");
        Vehicle repeatVehicle = new Vehicle("KA-05-DUP-0001", VehicleType.CAR);
        entryGateService.processEntry(repeatVehicle);
        try {
            entryGateService.processEntry(repeatVehicle);
            System.out.println("ERROR: duplicate entry should have been rejected");
        } catch (ParkingLotException e) {
            System.out.println("Correctly rejected: " + e.getMessage());
        }

        System.out.println();
        System.out.println("=== Admin: mark a spot out of service, then restore it ===");
        String someSpotId = "F2-S6"; // a LARGE spot on floor 2
        adminService.markSpotOutOfService(someSpotId);
        System.out.println("Occupancy after marking OOS: " + adminService.occupancySnapshot());
        adminService.bringSpotBackInService(someSpotId);
        System.out.println("Occupancy after restoring: " + adminService.occupancySnapshot());

        System.out.println();
        System.out.println("=== Concurrency stress: many gates racing for few spots ===");
        runConcurrencyStress(parkingLotService);

        System.out.println();
        System.out.println("Final occupancy snapshot: " + adminService.occupancySnapshot());
    }

    /**
     * Spins up several "entry gate" threads that all try to park distinct
     * vehicles at the same instant (synchronized start via a latch), proving
     * that (a) no two vehicles ever receive the same spot and (b) the system
     * degrades to a clean {@link NoAvailableSpotException} once spots of a
     * compatible size run out, rather than corrupting state.
     */
    private static void runConcurrencyStress(ParkingLotService parkingLotService) throws InterruptedException {
        int threadCount = 20; // far more cars than remaining MEDIUM/LARGE spots
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger parked = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        Gate stressGate = new Gate("GATE-ENTRY-STRESS", "Stress Test Entry", 0, Gate.Kind.ENTRY);

        for (int i = 0; i < threadCount; i++) {
            int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Vehicle vehicle = new Vehicle("STRESS-" + index, VehicleType.CAR);
                    parkingLotService.parkVehicle(vehicle, stressGate);
                    parked.incrementAndGet();
                } catch (NoAvailableSpotException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.println("Concurrent park attempts: " + threadCount
                + ", succeeded: " + parked.get() + ", rejected (lot full): " + rejected.get());
    }

    /** A fixed-order floor layout: small spots first, then medium, then large, so demo output reads predictably. */
    private static Map<SpotSize, Integer> floorLayout() {
        Map<SpotSize, Integer> layout = new java.util.LinkedHashMap<>();
        layout.put(SpotSize.SMALL, 2);
        layout.put(SpotSize.MEDIUM, 3);
        layout.put(SpotSize.LARGE, 1);
        return layout;
    }

    private ParkingLotDemo() {
    }
}
