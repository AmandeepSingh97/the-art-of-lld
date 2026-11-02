package lld.bookmyshow.service;

import lld.bookmyshow.util.IdGenerator;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Test/demo payment gateway with no real external dependency. Simulates
 * success/failure via a pluggable {@link Predicate} so tests can force
 * deterministic failures, while still exercising the real idempotency
 * guarantee that a production gateway must provide.
 *
 * <p><b>Idempotency:</b> {@link ConcurrentHashMap#computeIfAbsent} is used
 * so that, even if two threads call {@link #charge} with the same
 * {@code idempotencyKey} concurrently (e.g. a client retried a timed-out
 * request while the original was still in flight), the charge logic runs
 * for that key exactly once and every caller observes the same
 * {@link PaymentResult} — this is the idempotency guarantee
 * {@code BookingService#confirmBooking} relies on.
 */
public class MockPaymentGateway implements PaymentGateway {

    private final ConcurrentHashMap<String, PaymentResult> resultsByIdempotencyKey = new ConcurrentHashMap<>();
    private final Predicate<String> shouldFail;

    public MockPaymentGateway() {
        this(key -> false);
    }

    public MockPaymentGateway(Predicate<String> shouldFail) {
        this.shouldFail = shouldFail;
    }

    @Override
    public PaymentResult charge(String idempotencyKey, BigDecimal amount) {
        return resultsByIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
            if (shouldFail.test(key)) {
                return new PaymentResult(false, null, "Card declined");
            }
            return new PaymentResult(true, IdGenerator.uuid(), null);
        });
    }

    @Override
    public void refund(String paymentReferenceId, BigDecimal amount) {
        // Mock: nothing external to call. A real gateway adapter would issue a refund API call here.
    }
}
