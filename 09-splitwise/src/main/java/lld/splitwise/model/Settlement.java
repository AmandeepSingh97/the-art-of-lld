package lld.splitwise.model;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable record of a "settle up" payment — money that actually moved
 * (outside the system, e.g. cash/UPI/bank transfer) from {@code fromUserId}
 * to {@code toUserId}, which the ledger must reconcile against.
 *
 * <p>Like {@link Expense}, settlements are append-only facts, never mutated.
 */
public final class Settlement {

    private final String id;
    private final String groupId; // nullable
    private final String fromUserId; // payer: the person who owed money and is paying it off
    private final String toUserId;   // payee: the person who was owed money
    private final Money amount;
    private final Instant createdAt;

    public Settlement(String id, String groupId, String fromUserId, String toUserId, Money amount, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.groupId = groupId;
        this.fromUserId = Objects.requireNonNull(fromUserId, "fromUserId");
        this.toUserId = Objects.requireNonNull(toUserId, "toUserId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");

        if (fromUserId.equals(toUserId)) {
            throw new IllegalArgumentException("A user cannot settle up with themselves: " + fromUserId);
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Settlement amount must be positive, was: " + amount);
        }
    }

    public String id() {
        return id;
    }

    public String groupId() {
        return groupId;
    }

    public String fromUserId() {
        return fromUserId;
    }

    public String toUserId() {
        return toUserId;
    }

    public Money amount() {
        return amount;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Settlement that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Settlement{%s -> %s, amount=%s}".formatted(fromUserId, toUserId, amount);
    }
}
