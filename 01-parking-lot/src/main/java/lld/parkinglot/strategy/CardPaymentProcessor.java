package lld.parkinglot.strategy;

import lld.parkinglot.enums.PaymentMethod;
import lld.parkinglot.enums.PaymentStatus;
import lld.parkinglot.model.Payment;
import lld.parkinglot.util.IdGenerator;
import lld.parkinglot.util.Money;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Simulated card-network call. In production this would call out to a PSP
 * (Stripe/Braintree/etc.) over the network and would need retry/timeout
 * handling - see {@code STAFF_LEVEL_DEEP_DIVE.md} for the idempotency
 * discussion that a real network call requires.
 */
public final class CardPaymentProcessor implements PaymentProcessor {

    private final IdGenerator idGenerator = new IdGenerator("PAY-CARD");

    @Override
    public Payment charge(String ticketId, Money amount) {
        PaymentStatus status = amount.asBigDecimal().compareTo(BigDecimal.ZERO) > 0
                ? PaymentStatus.SUCCESS
                : PaymentStatus.FAILED;
        return new Payment(idGenerator.next(), ticketId, amount, PaymentMethod.CREDIT_CARD, status, Instant.now());
    }
}
