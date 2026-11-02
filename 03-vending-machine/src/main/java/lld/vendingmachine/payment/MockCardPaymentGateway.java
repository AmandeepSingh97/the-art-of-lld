package lld.vendingmachine.payment;

import lld.vendingmachine.domain.Money;
import lld.vendingmachine.exceptions.PaymentDeclinedException;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A deterministic in-memory stand-in for a real card network, suitable for
 * demos and tests. A production implementation would call out over HTTPS to a
 * PCI-compliant processor and handle network retries/timeouts explicitly
 * (see STAFF_LEVEL_DEEP_DIVE.md).
 */
public final class MockCardPaymentGateway implements CardPaymentGateway {

    private final AtomicLong authCounter = new AtomicLong();
    private final boolean alwaysDecline;

    public MockCardPaymentGateway() {
        this(false);
    }

    /** @param alwaysDecline when true, every charge is declined — useful for failure-path tests. */
    public MockCardPaymentGateway(boolean alwaysDecline) {
        this.alwaysDecline = alwaysDecline;
    }

    @Override
    public String charge(Money amount) throws PaymentDeclinedException {
        if (alwaysDecline) {
            throw new PaymentDeclinedException("issuer declined (mock)");
        }
        if (amount.isZero()) {
            throw new PaymentDeclinedException("cannot charge a zero amount");
        }
        return "AUTH-" + authCounter.incrementAndGet();
    }
}
