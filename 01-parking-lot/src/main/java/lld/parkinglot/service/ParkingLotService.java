package lld.parkinglot.service;

import lld.parkinglot.enums.PaymentMethod;
import lld.parkinglot.exception.InvalidTicketException;
import lld.parkinglot.exception.NoAvailableSpotException;
import lld.parkinglot.exception.VehicleAlreadyParkedException;
import lld.parkinglot.model.ParkingSpot;
import lld.parkinglot.model.Payment;
import lld.parkinglot.model.Ticket;
import lld.parkinglot.model.Vehicle;
import lld.parkinglot.model.Gate;
import lld.parkinglot.enums.TicketStatus;
import lld.parkinglot.repository.SpotRepository;
import lld.parkinglot.repository.TicketRepository;
import lld.parkinglot.strategy.PricingStrategy;
import lld.parkinglot.strategy.SpotAllocationStrategy;
import lld.parkinglot.util.IdGenerator;
import lld.parkinglot.util.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * Central facade coordinating allocation, ticketing, pricing and payment.
 * This is the one class every gate talks to; it deliberately contains no
 * business rule about <em>which</em> spot to pick or <em>how much</em> to
 * charge - those are injected strategies - so this class's only job is
 * sequencing the steps correctly and keeping them consistent under
 * concurrent use.
 *
 * <h2>Concurrency design</h2>
 * {@code parkVehicle} is safe to call concurrently from any number of entry
 * gates without any lock owned by this class, because every step it
 * performs is already individually atomic and the steps are composed with
 * explicit compensating rollback on failure:
 * <ol>
 *   <li>{@link TicketRepository#tryClaimPlate} atomically claims the license
 *       plate (CAS-like via {@code ConcurrentHashMap.putIfAbsent}). Two
 *       threads processing the same plate at the same instant: exactly one
 *       wins, the other gets {@link VehicleAlreadyParkedException}.</li>
 *   <li>{@link SpotAllocationStrategy#reserveSpotFor} atomically reserves a
 *       spot (see {@code InMemorySpotRepository}'s index-as-sole-gate
 *       design). Two threads racing for the same nearest spot: exactly one
 *       wins, the other proceeds to the next-nearest candidate.</li>
 *   <li>If reservation fails, the plate claim is rolled back so the vehicle
 *       can be retried later without being permanently "stuck" claimed.</li>
 *   <li>{@link ParkingSpot#occupy} is the final, also-atomic, state
 *       transition that commits the allocation.</li>
 * </ol>
 * No step holds a lock while calling another component, so this design
 * cannot deadlock: there is simply no scenario where this class waits while
 * holding a resource another thread needs.
 *
 * <p>{@code unparkVehicle} does not need an equivalent claim/rollback dance
 * for the ticket itself because a given {@code ticketId} is only ever
 * handed to one driver and one exit gate; the only shared mutable resource
 * it touches (the {@link ParkingSpot}) is freed via the same atomic
 * {@code vacate()} CAS used everywhere else.
 */
public final class ParkingLotService {

    private final SpotRepository spotRepository;
    private final TicketRepository ticketRepository;
    private final SpotAllocationStrategy allocationStrategy;
    private final PricingStrategy pricingStrategy;
    private final PaymentService paymentService;
    private final IdGenerator ticketIdGenerator = new IdGenerator("TICKET");

    public ParkingLotService(SpotRepository spotRepository,
                              TicketRepository ticketRepository,
                              SpotAllocationStrategy allocationStrategy,
                              PricingStrategy pricingStrategy,
                              PaymentService paymentService) {
        this.spotRepository = Objects.requireNonNull(spotRepository, "spotRepository");
        this.ticketRepository = Objects.requireNonNull(ticketRepository, "ticketRepository");
        this.allocationStrategy = Objects.requireNonNull(allocationStrategy, "allocationStrategy");
        this.pricingStrategy = Objects.requireNonNull(pricingStrategy, "pricingStrategy");
        this.paymentService = Objects.requireNonNull(paymentService, "paymentService");
    }

    /**
     * Admits {@code vehicle} through {@code entryGate}: claims the plate,
     * reserves the nearest compatible spot, occupies it, and issues a ticket.
     *
     * @throws VehicleAlreadyParkedException if this license plate already has an active stay
     * @throws NoAvailableSpotException       if no compatible, in-service spot is currently free
     */
    public Ticket parkVehicle(Vehicle vehicle, Gate entryGate) {
        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(entryGate, "entryGate");

        if (!ticketRepository.tryClaimPlate(vehicle.getLicensePlate())) {
            throw new VehicleAlreadyParkedException(
                    "Vehicle " + vehicle.getLicensePlate() + " already has an active parking session");
        }

        ParkingSpot spot = allocationStrategy.reserveSpotFor(vehicle, entryGate).orElse(null);
        if (spot == null) {
            ticketRepository.releaseClaim(vehicle.getLicensePlate());
            throw new NoAvailableSpotException(
                    "No available spot for vehicle type " + vehicle.getType() + " entering at " + entryGate);
        }

        try {
            spot.occupy(vehicle);
        } catch (RuntimeException e) {
            spotRepository.releaseReservation(spot);
            ticketRepository.releaseClaim(vehicle.getLicensePlate());
            throw e;
        }

        Ticket ticket = new Ticket(ticketIdGenerator.next(), vehicle, spot, entryGate.getId(), Instant.now());
        ticketRepository.save(ticket);
        return ticket;
    }

    /**
     * Settles and closes an active ticket: computes the fee, charges the
     * driver, frees the spot, and closes the ticket. The license plate claim
     * is released last, after payment succeeds, so a failed payment leaves
     * the vehicle's stay (and spot) intact for a retry rather than silently
     * freeing a spot nobody paid for.
     *
     * @throws InvalidTicketException                        if the ticket id is unknown or already closed
     * @throws lld.parkinglot.exception.PaymentFailedException if the payment attempt fails
     */
    public ExitReceipt unparkVehicle(String ticketId, Gate exitGate, PaymentMethod paymentMethod) {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(exitGate, "exitGate");
        Objects.requireNonNull(paymentMethod, "paymentMethod");

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new InvalidTicketException("No ticket found with id " + ticketId));
        if (ticket.getStatus() != TicketStatus.ACTIVE) {
            throw new InvalidTicketException("Ticket " + ticketId + " is not active (status=" + ticket.getStatus() + ")");
        }

        Instant exitTime = Instant.now();
        Money fee = pricingStrategy.calculateFee(ticket, exitTime);

        Payment payment = paymentService.pay(ticketId, fee, paymentMethod);

        ticket.close(exitGate.getId(), exitTime, fee);
        spotRepository.freeSpot(ticket.getSpot());
        ticketRepository.releaseClaim(ticket.getVehicle().getLicensePlate());

        return new ExitReceipt(ticket, payment);
    }
}
