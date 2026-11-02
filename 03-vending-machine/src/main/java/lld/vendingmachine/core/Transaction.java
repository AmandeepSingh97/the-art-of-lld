package lld.vendingmachine.core;

import lld.vendingmachine.domain.Denomination;
import lld.vendingmachine.domain.Money;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Immutable audit record of one completed purchase. Kept deliberately simple
 * (no persistence) — see README section 12 for why a full database is not
 * warranted for the core interview-scope solution, and section 17 for how
 * this would be persisted/streamed in production.
 */
public record Transaction(
        String transactionId,
        String productId,
        String productName,
        Money pricePaid,
        String paymentDescription,
        Map<Denomination, Integer> changeDispensed,
        Instant timestamp) {

    public Transaction {
        changeDispensed = Collections.unmodifiableMap(changeDispensed);
    }

    @Override
    public String toString() {
        return "Transaction{" + transactionId + ", " + productName + ", paid=" + pricePaid
                + ", via=" + paymentDescription + ", change=" + changeDispensed
                + ", at=" + timestamp + '}';
    }
}
