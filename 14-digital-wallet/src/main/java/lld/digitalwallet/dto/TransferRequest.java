package lld.digitalwallet.dto;

import lld.digitalwallet.model.Money;

import java.util.Objects;

/** Request to move money between two wallet accounts. */
public final class TransferRequest {
    private final String sourceAccountId;
    private final String destinationAccountId;
    private final Money amount;
    private final String idempotencyKey;
    private final String description;

    public TransferRequest(String sourceAccountId, String destinationAccountId, Money amount,
                            String idempotencyKey, String description) {
        this.sourceAccountId = Objects.requireNonNull(sourceAccountId);
        this.destinationAccountId = Objects.requireNonNull(destinationAccountId);
        this.amount = Objects.requireNonNull(amount);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.description = description == null ? "" : description;
    }

    public String sourceAccountId() { return sourceAccountId; }
    public String destinationAccountId() { return destinationAccountId; }
    public Money amount() { return amount; }
    public String idempotencyKey() { return idempotencyKey; }
    public String description() { return description; }
}
