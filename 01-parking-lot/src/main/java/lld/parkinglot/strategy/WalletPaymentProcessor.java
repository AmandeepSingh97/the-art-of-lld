package lld.parkinglot.strategy;

import lld.parkinglot.enums.PaymentMethod;
import lld.parkinglot.enums.PaymentStatus;
import lld.parkinglot.model.Payment;
import lld.parkinglot.util.IdGenerator;
import lld.parkinglot.util.Money;

import java.time.Instant;

/** Simulated UPI/mobile-wallet settlement. */
public final class WalletPaymentProcessor implements PaymentProcessor {

    private final IdGenerator idGenerator = new IdGenerator("PAY-UPI");

    @Override
    public Payment charge(String ticketId, Money amount) {
        return new Payment(idGenerator.next(), ticketId, amount, PaymentMethod.UPI_OR_WALLET, PaymentStatus.SUCCESS, Instant.now());
    }
}
