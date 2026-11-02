package lld.parkinglot.repository;

import lld.parkinglot.model.Ticket;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link TicketRepository}. {@code activePlates} is a
 * {@link ConcurrentHashMap} used purely as a concurrent set (values are a
 * sentinel) so that {@link #tryClaimPlate} can use {@code putIfAbsent} as an
 * atomic test-and-set, which is what prevents two concurrent entry-gate
 * threads from both successfully starting a stay for the same license
 * plate.
 */
public final class InMemoryTicketRepository implements TicketRepository {

    private static final Object PRESENT = new Object();

    private final Map<String, Ticket> ticketsById = new ConcurrentHashMap<>();
    private final Map<String, Object> activePlates = new ConcurrentHashMap<>();

    @Override
    public void save(Ticket ticket) {
        ticketsById.put(ticket.getId(), ticket);
    }

    @Override
    public Optional<Ticket> findById(String ticketId) {
        return Optional.ofNullable(ticketsById.get(ticketId));
    }

    @Override
    public boolean tryClaimPlate(String licensePlate) {
        return activePlates.putIfAbsent(licensePlate, PRESENT) == null;
    }

    @Override
    public void releaseClaim(String licensePlate) {
        activePlates.remove(licensePlate);
    }
}
