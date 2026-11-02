package lld.fooddelivery.domain;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A payment attempt against an {@link Order}. One order may, over its lifetime, be associated
 * with at most one <em>successful</em> charge and at most one refund of that charge; this is
 * enforced by {@link lld.fooddelivery.service.PaymentService}, not by this class, which is a
 * simple state holder.
 *
 * <p><b>Thread-safety:</b> {@code status} uses CAS so that a concurrent duplicate refund
 * request (e.g. a retried network call from an at-least-once delivery queue) can only succeed
 * once: the second caller's {@code compareAndSet(SUCCESS, REFUNDED)} fails because the first
 * caller already moved the status to {@code REFUNDED}, giving idempotent refund semantics
 * without an external deduplication table.
 */
public final class Payment {

    private final String id;
    private final String orderId;
    private final Money amount;
    private final AtomicReference<PaymentStatus> status;

    public Payment(String id, String orderId, Money amount) {
        this.id = Objects.requireNonNull(id, "id");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.status = new AtomicReference<>(PaymentStatus.PENDING);
    }

    public String id() {
        return id;
    }

    public String orderId() {
        return orderId;
    }

    public Money amount() {
        return amount;
    }

    public PaymentStatus status() {
        return status.get();
    }

    public boolean markSuccess() {
        return status.compareAndSet(PaymentStatus.PENDING, PaymentStatus.SUCCESS);
    }

    public boolean markFailed() {
        return status.compareAndSet(PaymentStatus.PENDING, PaymentStatus.FAILED);
    }

    /** Idempotent: returns {@code false} if the payment was not in {@code SUCCESS} (e.g. duplicate refund call). */
    public boolean markRefunded() {
        return status.compareAndSet(PaymentStatus.SUCCESS, PaymentStatus.REFUNDED);
    }

    @Override
    public String toString() {
        return "Payment{" + id + ", order=" + orderId + ", amount=" + amount + ", status=" + status.get() + '}';
    }
}
