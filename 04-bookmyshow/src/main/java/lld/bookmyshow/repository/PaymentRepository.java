package lld.bookmyshow.repository;

import lld.bookmyshow.model.Payment;

public class PaymentRepository extends InMemoryRepository<String, Payment> {
    public PaymentRepository() {
        super(Payment::getId);
    }
}
