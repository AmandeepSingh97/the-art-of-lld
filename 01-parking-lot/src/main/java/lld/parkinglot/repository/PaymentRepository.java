package lld.parkinglot.repository;

import lld.parkinglot.model.Payment;

import java.util.List;

/** Persistence seam for payment records (append-only audit trail). */
public interface PaymentRepository {

    void save(Payment payment);

    List<Payment> findByTicketId(String ticketId);
}
