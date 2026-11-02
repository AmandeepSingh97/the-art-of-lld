package lld.splitwise.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An immutable record of money spent by one user on behalf of a set of participants.
 *
 * <p>An {@link Expense} is a value object once created: Splitwise-like systems
 * never mutate a posted expense in place (editing an expense is modelled as
 * "delete + recreate" in most real implementations, to keep the ledger an
 * append-only, auditable log). This class is therefore fully immutable, which
 * also makes it trivially thread-safe to share and read concurrently.
 *
 * <p>Invariant enforced at construction: {@code sum(shares.amount) == amount}.
 * This is the single most important domain invariant in the whole system —
 * if it is ever violated, the ledger will silently drift out of balance.
 */
public final class Expense {

    private final String id;
    private final String groupId; // nullable: null means a direct (non-group) expense between individuals
    private final String description;
    private final Money amount;
    private final String paidByUserId;
    private final SplitType splitType;
    private final List<ExpenseShare> shares;
    private final Instant createdAt;

    public Expense(String id, String groupId, String description, Money amount, String paidByUserId,
                   SplitType splitType, List<ExpenseShare> shares, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.groupId = groupId;
        this.description = Objects.requireNonNull(description, "description");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.paidByUserId = Objects.requireNonNull(paidByUserId, "paidByUserId");
        this.splitType = Objects.requireNonNull(splitType, "splitType");
        this.shares = List.copyOf(Objects.requireNonNull(shares, "shares"));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");

        Money total = Money.ZERO;
        for (ExpenseShare share : this.shares) {
            total = total.add(share.amount());
        }
        if (!total.equals(amount)) {
            throw new IllegalStateException(
                    "Expense invariant violated: sum of shares (%s) != total amount (%s)".formatted(total, amount));
        }
    }

    public String id() {
        return id;
    }

    public String groupId() {
        return groupId;
    }

    public boolean isGroupExpense() {
        return groupId != null;
    }

    public String description() {
        return description;
    }

    public Money amount() {
        return amount;
    }

    public String paidByUserId() {
        return paidByUserId;
    }

    public SplitType splitType() {
        return splitType;
    }

    public List<ExpenseShare> shares() {
        return shares;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Expense expense)) return false;
        return id.equals(expense.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Expense{id='%s', description='%s', amount=%s, paidBy='%s', type=%s}"
                .formatted(id, description, amount, paidByUserId, splitType);
    }
}
