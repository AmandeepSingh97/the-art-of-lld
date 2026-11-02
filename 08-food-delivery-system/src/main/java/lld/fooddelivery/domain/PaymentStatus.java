package lld.fooddelivery.domain;

/**
 * Lifecycle of a {@link Payment}. {@code REFUNDED} is reachable only from {@code SUCCESS}
 * (you cannot refund a payment that never succeeded), and {@code FAILED} is terminal (a failed
 * charge is retried as a brand-new {@code Payment}, never resurrected in place — this keeps
 * each payment attempt an immutable audit record).
 */
public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED,
    REFUNDED
}
