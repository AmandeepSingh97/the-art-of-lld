package lld.parkinglot.repository;

import lld.parkinglot.model.Payment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory, append-only {@link PaymentRepository}. A {@link CopyOnWriteArrayList}
 * per ticket is appropriate here: writes (one payment attempt per exit, rare
 * retries) are infrequent while reads (receipt lookups, refund audits) can be
 * frequent and must never block on a writer.
 */
public final class InMemoryPaymentRepository implements PaymentRepository {

    private final Map<String, List<Payment>> paymentsByTicketId = new ConcurrentHashMap<>();

    @Override
    public void save(Payment payment) {
        paymentsByTicketId
                .computeIfAbsent(payment.getTicketId(), id -> new CopyOnWriteArrayList<>())
                .add(payment);
    }

    @Override
    public List<Payment> findByTicketId(String ticketId) {
        return List.copyOf(paymentsByTicketId.getOrDefault(ticketId, List.of()));
    }
}
