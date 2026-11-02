package lld.parkinglot.repository;

import lld.parkinglot.model.Ticket;

import java.util.Optional;

/**
 * Persistence seam for tickets. In-memory today; a real deployment backs
 * this with a database table (see README section 12).
 *
 * <p>{@link #tryClaimPlate} / {@link #releaseClaim} exist specifically to
 * close the race between two entry gates processing the same license plate
 * at the same instant - see the class-level concurrency note on
 * {@code ParkingLotService} for how these are composed with spot
 * reservation into one atomic-enough "begin parking" operation.
 */
public interface TicketRepository {

    void save(Ticket ticket);

    Optional<Ticket> findById(String ticketId);

    /**
     * Atomically claims {@code licensePlate} as "has an active stay in progress".
     *
     * @return true if the claim succeeded (no other active stay for this plate), false otherwise.
     */
    boolean tryClaimPlate(String licensePlate);

    /** Releases a plate claim, e.g. on exit, or to roll back a claim that could not be completed into a parked ticket. */
    void releaseClaim(String licensePlate);
}
