package lld.bookmyshow.service;

import java.math.BigDecimal;

/**
 * Abstraction over an external payment processor. Kept narrow (charge +
 * refund) so the booking flow is decoupled from any specific provider's
 * SDK/quirks — a real deployment would have e.g. a Stripe/Razorpay adapter
 * implementing this interface.
 */
public interface PaymentGateway {

    /**
     * Charges {@code amount}, keyed by {@code idempotencyKey}. Implementations
     * must guarantee that repeated calls with the <b>same</b> idempotency key
     * return the original outcome without charging twice — this is what lets
     * {@code BookingService} safely retry a confirm call after a timeout
     * without risking a double charge.
     */
    PaymentResult charge(String idempotencyKey, BigDecimal amount);

    /** Refunds a previously successful charge, identified by its payment reference id. */
    void refund(String paymentReferenceId, BigDecimal amount);

    /** Outcome of a charge attempt. */
    record PaymentResult(boolean success, String referenceId, String failureReason) {
    }
}
