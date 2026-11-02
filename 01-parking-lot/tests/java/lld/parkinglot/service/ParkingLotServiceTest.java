package lld.parkinglot.service;

import lld.parkinglot.enums.PaymentMethod;
import lld.parkinglot.enums.PaymentStatus;
import lld.parkinglot.enums.SpotSize;
import lld.parkinglot.enums.TicketStatus;
import lld.parkinglot.enums.VehicleType;
import lld.parkinglot.exception.InvalidTicketException;
import lld.parkinglot.exception.NoAvailableSpotException;
import lld.parkinglot.exception.PaymentFailedException;
import lld.parkinglot.exception.VehicleAlreadyParkedException;
import lld.parkinglot.model.Floor;
import lld.parkinglot.model.Gate;
import lld.parkinglot.model.Payment;
import lld.parkinglot.model.ParkingSpot;
import lld.parkinglot.model.Ticket;
import lld.parkinglot.model.Vehicle;
import lld.parkinglot.repository.InMemoryPaymentRepository;
import lld.parkinglot.repository.InMemorySpotRepository;
import lld.parkinglot.repository.InMemoryTicketRepository;
import lld.parkinglot.repository.PaymentRepository;
import lld.parkinglot.repository.SpotRepository;
import lld.parkinglot.repository.TicketRepository;
import lld.parkinglot.strategy.FlatRatePricingStrategy;
import lld.parkinglot.strategy.HourlyPricingStrategy;
import lld.parkinglot.strategy.NearestAvailableSpotAllocationStrategy;
import lld.parkinglot.strategy.PaymentProcessor;
import lld.parkinglot.strategy.PricingStrategy;
import lld.parkinglot.strategy.SpotAllocationStrategy;
import lld.parkinglot.util.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkingLotServiceTest {

    private SpotRepository spotRepository;
    private TicketRepository ticketRepository;
    private PaymentRepository paymentRepository;
    private Gate entryGate;
    private Gate exitGate;

    @BeforeEach
    void setUp() {
        Floor floor0 = new Floor(0, List.of(
                new ParkingSpot("F0-S1", 0, 1, SpotSize.MEDIUM),
                new ParkingSpot("F0-S2", 0, 2, SpotSize.MEDIUM),
                new ParkingSpot("F0-S3", 0, 3, SpotSize.LARGE)));
        spotRepository = new InMemorySpotRepository(List.of(floor0));
        ticketRepository = new InMemoryTicketRepository();
        paymentRepository = new InMemoryPaymentRepository();
        entryGate = new Gate("ENTRY-1", "Entry 1", 0, Gate.Kind.ENTRY);
        exitGate = new Gate("EXIT-1", "Exit 1", 0, Gate.Kind.EXIT);
    }

    private ParkingLotService serviceWithHourlyPricingAnd(PaymentProcessor processor) {
        SpotAllocationStrategy allocationStrategy = new NearestAvailableSpotAllocationStrategy(spotRepository);
        PricingStrategy pricingStrategy = new HourlyPricingStrategy(Map.of(
                VehicleType.CAR, Money.of(2.0), VehicleType.MOTORCYCLE, Money.of(1.0), VehicleType.BUS_OR_TRUCK, Money.of(5.0)));
        Map<PaymentMethod, PaymentProcessor> processors = new EnumMap<>(PaymentMethod.class);
        processors.put(PaymentMethod.CASH, processor);
        PaymentService paymentService = new PaymentService(processors, paymentRepository);
        return new ParkingLotService(spotRepository, ticketRepository, allocationStrategy, pricingStrategy, paymentService);
    }

    private static PaymentProcessor alwaysSucceeds() {
        return (ticketId, amount) -> new Payment("PAY-1", ticketId, amount, PaymentMethod.CASH, PaymentStatus.SUCCESS, Instant.now());
    }

    private static PaymentProcessor alwaysFails() {
        return (ticketId, amount) -> new Payment("PAY-1", ticketId, amount, PaymentMethod.CASH, PaymentStatus.FAILED, Instant.now());
    }

    @Test
    void parkThenUnparkHappyPathClosesTicketAndFreesSpot() {
        ParkingLotService service = serviceWithHourlyPricingAnd(alwaysSucceeds());
        Vehicle vehicle = new Vehicle("KA-01-AA-0001", VehicleType.CAR);

        Ticket ticket = service.parkVehicle(vehicle, entryGate);
        assertEquals(TicketStatus.ACTIVE, ticket.getStatus());

        ExitReceipt receipt = service.unparkVehicle(ticket.getId(), exitGate, PaymentMethod.CASH);

        assertEquals(TicketStatus.PAID_AND_CLOSED, ticket.getStatus());
        assertEquals(lld.parkinglot.enums.SpotStatus.AVAILABLE, ticket.getSpot().getStatus());
        assertEquals(Money.of(2.0), receipt.getPayment().getAmount());
    }

    @Test
    void sameLicensePlateCannotParkTwiceConcurrently() {
        ParkingLotService service = serviceWithHourlyPricingAnd(alwaysSucceeds());
        Vehicle vehicle = new Vehicle("KA-01-AA-0001", VehicleType.CAR);
        service.parkVehicle(vehicle, entryGate);

        assertThrows(VehicleAlreadyParkedException.class, () -> service.parkVehicle(vehicle, entryGate));
    }

    @Test
    void sameLicensePlateCanParkAgainAfterExiting() {
        ParkingLotService service = serviceWithHourlyPricingAnd(alwaysSucceeds());
        Vehicle vehicle = new Vehicle("KA-01-AA-0001", VehicleType.CAR);
        Ticket first = service.parkVehicle(vehicle, entryGate);
        service.unparkVehicle(first.getId(), exitGate, PaymentMethod.CASH);

        Ticket second = service.parkVehicle(vehicle, entryGate);
        assertEquals(TicketStatus.ACTIVE, second.getStatus());
    }

    @Test
    void parkingFailsCleanlyWhenLotHasNoCompatibleSpot() {
        ParkingLotService service = serviceWithHourlyPricingAnd(alwaysSucceeds());
        // Fill both MEDIUM spots, then LARGE, then a 4th car should fail (no spot left that fits a car).
        service.parkVehicle(new Vehicle("KA-1", VehicleType.CAR), entryGate);
        service.parkVehicle(new Vehicle("KA-2", VehicleType.CAR), entryGate);
        service.parkVehicle(new Vehicle("KA-3", VehicleType.CAR), entryGate);

        assertThrows(NoAvailableSpotException.class, () -> service.parkVehicle(new Vehicle("KA-4", VehicleType.CAR), entryGate));
    }

    @Test
    void failedPaymentLeavesTicketActiveAndSpotOccupied() {
        ParkingLotService service = serviceWithHourlyPricingAnd(alwaysFails());
        Vehicle vehicle = new Vehicle("KA-01-AA-0001", VehicleType.CAR);
        Ticket ticket = service.parkVehicle(vehicle, entryGate);

        assertThrows(PaymentFailedException.class, () -> service.unparkVehicle(ticket.getId(), exitGate, PaymentMethod.CASH));

        assertEquals(TicketStatus.ACTIVE, ticket.getStatus());
        assertEquals(lld.parkinglot.enums.SpotStatus.OCCUPIED, ticket.getSpot().getStatus());
    }

    @Test
    void unknownTicketIdIsRejected() {
        ParkingLotService service = serviceWithHourlyPricingAnd(alwaysSucceeds());
        assertThrows(InvalidTicketException.class, () -> service.unparkVehicle("NOPE", exitGate, PaymentMethod.CASH));
    }

    @Test
    void cannotUnparkTheSameTicketTwice() {
        ParkingLotService service = serviceWithHourlyPricingAnd(alwaysSucceeds());
        Vehicle vehicle = new Vehicle("KA-01-AA-0001", VehicleType.CAR);
        Ticket ticket = service.parkVehicle(vehicle, entryGate);
        service.unparkVehicle(ticket.getId(), exitGate, PaymentMethod.CASH);

        assertThrows(InvalidTicketException.class, () -> service.unparkVehicle(ticket.getId(), exitGate, PaymentMethod.CASH));
    }

    /**
     * End-to-end concurrency test exercising multiple "entry gate" threads
     * against a lot with exactly as many compatible spots as half the
     * threads, all starting at once. Asserts: (a) the number of successful
     * parks equals exactly the number of compatible spots (no overbooking,
     * no lost spots), and (b) every successful park received a distinct spot.
     */
    @Test
    void manyEntryGatesCannotOverbookOrDoubleAllocateSpotsConcurrently() throws InterruptedException {
        int spotCount = 10;
        List<ParkingSpot> spots = IntStream.rangeClosed(1, spotCount)
                .mapToObj(i -> new ParkingSpot("S" + i, 0, i, SpotSize.MEDIUM))
                .collect(Collectors.toList());
        spotRepository = new InMemorySpotRepository(List.of(new Floor(0, spots)));
        ParkingLotService service = serviceWithHourlyPricingAnd(alwaysSucceeds());

        int threadCount = 40;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger succeeded = new AtomicInteger();
        Set<String> allocatedSpotIds = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threadCount; i++) {
            int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Ticket ticket = service.parkVehicle(new Vehicle("CAR-" + index, VehicleType.CAR), entryGate);
                    succeeded.incrementAndGet();
                    allocatedSpotIds.add(ticket.getSpot().getId());
                } catch (NoAvailableSpotException expected) {
                    // Lot full: expected once spotCount vehicles have succeeded.
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

        assertEquals(spotCount, succeeded.get());
        assertEquals(spotCount, allocatedSpotIds.size());
    }

    @Test
    void flatRatePricingIgnoresDuration() {
        SpotAllocationStrategy allocationStrategy = new NearestAvailableSpotAllocationStrategy(spotRepository);
        PricingStrategy flatRate = new FlatRatePricingStrategy(Map.of(VehicleType.CAR, Money.of(10.0)));
        Map<PaymentMethod, PaymentProcessor> processors = new EnumMap<>(PaymentMethod.class);
        processors.put(PaymentMethod.CASH, alwaysSucceeds());
        PaymentService paymentService = new PaymentService(processors, paymentRepository);
        ParkingLotService service = new ParkingLotService(spotRepository, ticketRepository, allocationStrategy, flatRate, paymentService);

        Ticket ticket = service.parkVehicle(new Vehicle("KA-1", VehicleType.CAR), entryGate);
        ExitReceipt receipt = service.unparkVehicle(ticket.getId(), exitGate, PaymentMethod.CASH);

        assertEquals(Money.of(10.0), receipt.getPayment().getAmount());
    }
}
