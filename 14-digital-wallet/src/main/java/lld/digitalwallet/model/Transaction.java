package lld.digitalwallet.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable record of a business-level operation requested by a client
 * (deposit, withdrawal, transfer, or reversal). A {@code Transaction} is the
 * unit the client's idempotency key and API responses are keyed on; it is
 * backed by one or two {@link LedgerEntry} rows that are the actual
 * double-entry postings.
 */
public final class Transaction {

    private final String transactionId;
    private final TransactionType type;
    private final String sourceAccountId;   // null for pure DEPOSIT
    private final String destinationAccountId; // null for pure WITHDRAWAL
    private final Money amount;
    private final TransactionStatus status;
    private final Instant createdAt;
    private final String idempotencyKey;
    private final String description;
    private final String reversalOfTransactionId; // non-null only for REVERSAL transactions

    private Transaction(Builder b) {
        this.transactionId = Objects.requireNonNull(b.transactionId);
        this.type = Objects.requireNonNull(b.type);
        this.sourceAccountId = b.sourceAccountId;
        this.destinationAccountId = b.destinationAccountId;
        this.amount = Objects.requireNonNull(b.amount);
        this.status = Objects.requireNonNull(b.status);
        this.createdAt = Objects.requireNonNull(b.createdAt);
        this.idempotencyKey = Objects.requireNonNull(b.idempotencyKey);
        this.description = b.description == null ? "" : b.description;
        this.reversalOfTransactionId = b.reversalOfTransactionId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String transactionId() {
        return transactionId;
    }

    public TransactionType type() {
        return type;
    }

    public Optional<String> sourceAccountId() {
        return Optional.ofNullable(sourceAccountId);
    }

    public Optional<String> destinationAccountId() {
        return Optional.ofNullable(destinationAccountId);
    }

    public Money amount() {
        return amount;
    }

    public TransactionStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String description() {
        return description;
    }

    public Optional<String> reversalOfTransactionId() {
        return Optional.ofNullable(reversalOfTransactionId);
    }

    /** Returns a copy of this transaction with a new status (transactions are immutable; this never mutates in place). */
    public Transaction withStatus(TransactionStatus newStatus) {
        return builder()
                .transactionId(transactionId)
                .type(type)
                .sourceAccountId(sourceAccountId)
                .destinationAccountId(destinationAccountId)
                .amount(amount)
                .status(newStatus)
                .createdAt(createdAt)
                .idempotencyKey(idempotencyKey)
                .description(description)
                .reversalOfTransactionId(reversalOfTransactionId)
                .build();
    }

    public static String newTransactionId() {
        return "txn_" + UUID.randomUUID();
    }

    @Override
    public String toString() {
        return "Transaction{id=%s, type=%s, src=%s, dst=%s, amount=%s, status=%s}"
                .formatted(transactionId, type, sourceAccountId, destinationAccountId, amount, status);
    }

    public static final class Builder {
        private String transactionId;
        private TransactionType type;
        private String sourceAccountId;
        private String destinationAccountId;
        private Money amount;
        private TransactionStatus status;
        private Instant createdAt;
        private String idempotencyKey;
        private String description;
        private String reversalOfTransactionId;

        public Builder transactionId(String v) { this.transactionId = v; return this; }
        public Builder type(TransactionType v) { this.type = v; return this; }
        public Builder sourceAccountId(String v) { this.sourceAccountId = v; return this; }
        public Builder destinationAccountId(String v) { this.destinationAccountId = v; return this; }
        public Builder amount(Money v) { this.amount = v; return this; }
        public Builder status(TransactionStatus v) { this.status = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder idempotencyKey(String v) { this.idempotencyKey = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder reversalOfTransactionId(String v) { this.reversalOfTransactionId = v; return this; }

        public Transaction build() {
            return new Transaction(this);
        }
    }
}
