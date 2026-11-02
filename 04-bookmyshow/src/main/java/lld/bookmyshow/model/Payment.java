package lld.bookmyshow.model;

import lld.bookmyshow.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Record of a payment attempt tied 1:1 to a {@link Booking}. Status is
 * mutable (INITIATED -&gt; SUCCESS/FAILED -&gt; REFUNDED) so it is guarded by an
 * {@link AtomicReference}, consistent with the rest of the state-machine
 * style used across the mutable entities in this system.
 */
public final class Payment {
    private final String id;
    private final String bookingId;
    private final BigDecimal amount;
    private final Instant createdAt;
    private final AtomicReference<PaymentStatus> status;

    public Payment(String id, String bookingId, BigDecimal amount, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.bookingId = Objects.requireNonNull(bookingId, "bookingId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = new AtomicReference<>(PaymentStatus.INITIATED);
    }

    public String getId() {
        return id;
    }

    public String getBookingId() {
        return bookingId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public PaymentStatus getStatus() {
        return status.get();
    }

    public void markSuccess() {
        status.set(PaymentStatus.SUCCESS);
    }

    public void markFailed() {
        status.set(PaymentStatus.FAILED);
    }

    public void markRefunded() {
        status.set(PaymentStatus.REFUNDED);
    }
}
