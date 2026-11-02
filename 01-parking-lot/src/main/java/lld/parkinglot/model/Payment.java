package lld.parkinglot.model;

import lld.parkinglot.enums.PaymentMethod;
import lld.parkinglot.enums.PaymentStatus;
import lld.parkinglot.util.Money;

import java.time.Instant;
import java.util.Objects;

/** An immutable record of a single payment attempt against a ticket. */
public final class Payment {

    private final String id;
    private final String ticketId;
    private final Money amount;
    private final PaymentMethod method;
    private final PaymentStatus status;
    private final Instant timestamp;

    public Payment(String id, String ticketId, Money amount, PaymentMethod method, PaymentStatus status, Instant timestamp) {
        this.id = Objects.requireNonNull(id, "id");
        this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.method = Objects.requireNonNull(method, "method");
        this.status = Objects.requireNonNull(status, "status");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    public String getId() {
        return id;
    }

    public String getTicketId() {
        return ticketId;
    }

    public Money getAmount() {
        return amount;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "Payment[" + id + ", ticket=" + ticketId + ", amount=" + amount + ", method=" + method + ", status=" + status + "]";
    }
}
