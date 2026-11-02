package lld.parkinglot.strategy;

import lld.parkinglot.enums.PaymentMethod;
import lld.parkinglot.enums.PaymentStatus;
import lld.parkinglot.model.Payment;
import lld.parkinglot.util.IdGenerator;
import lld.parkinglot.util.Money;

import java.time.Instant;

/** Cash cannot technically be "declined", so this processor always succeeds given a valid amount. */
public final class CashPaymentProcessor implements PaymentProcessor {

    private final IdGenerator idGenerator = new IdGenerator("PAY-CASH");

    @Override
    public Payment charge(String ticketId, Money amount) {
        return new Payment(idGenerator.next(), ticketId, amount, PaymentMethod.CASH, PaymentStatus.SUCCESS, Instant.now());
    }
}
