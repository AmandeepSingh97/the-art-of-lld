package lld.parkinglot.service;

import lld.parkinglot.enums.PaymentMethod;
import lld.parkinglot.enums.PaymentStatus;
import lld.parkinglot.exception.PaymentFailedException;
import lld.parkinglot.model.Payment;
import lld.parkinglot.repository.PaymentRepository;
import lld.parkinglot.strategy.PaymentProcessor;
import lld.parkinglot.util.Money;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Selects the correct {@link PaymentProcessor} for a {@link PaymentMethod}
 * (Strategy Pattern via a simple registry) and persists the resulting
 * {@link Payment}. Stateless aside from the registry and repository
 * reference, both of which are effectively immutable after construction -
 * safe to share across threads with no additional synchronization.
 */
public final class PaymentService {

    private final Map<PaymentMethod, PaymentProcessor> processorsByMethod;
    private final PaymentRepository paymentRepository;

    public PaymentService(Map<PaymentMethod, PaymentProcessor> processorsByMethod, PaymentRepository paymentRepository) {
        this.processorsByMethod = new EnumMap<>(Objects.requireNonNull(processorsByMethod, "processorsByMethod"));
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "paymentRepository");
    }

    /**
     * Charges {@code amount} via {@code method} and persists the outcome.
     *
     * @throws PaymentFailedException if the processor reports the charge failed
     */
    public Payment pay(String ticketId, Money amount, PaymentMethod method) {
        PaymentProcessor processor = processorsByMethod.get(method);
        if (processor == null) {
            throw new PaymentFailedException("No payment processor registered for method " + method);
        }
        Payment payment = processor.charge(ticketId, amount);
        paymentRepository.save(payment);
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new PaymentFailedException("Payment " + payment.getId() + " for ticket " + ticketId + " failed");
        }
        return payment;
    }
}
