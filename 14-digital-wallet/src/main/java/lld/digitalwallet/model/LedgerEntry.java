package lld.digitalwallet.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single immutable double-entry ledger posting against one account.
 *
 * <h2>The double-entry invariant</h2>
 * Every {@link Transaction} of type {@code TRANSFER} (or {@code REVERSAL} of
 * one) produces <b>exactly two</b> {@code LedgerEntry} rows that share the
 * same {@code transactionId}: one {@code DEBIT} against the source account
 * and one {@code CREDIT} against the destination account, both for the same
 * {@link Money} amount. A {@code DEPOSIT}/{@code WITHDRAWAL} produces a
 * single entry against the wallet account (the other leg is an external
 * system - the bank rail - outside this ledger's scope).
 *
 * <p>This is the classic "every debit has a matching credit" accounting
 * invariant. It lets us reconstruct, purely from the ledger, that money was
 * never created or destroyed: summing all entries for a transaction must
 * net to zero, and summing all entries for an account reconstructs its
 * balance independently of the mutable {@link Account#getBalanceSnapshot()}
 * field - a powerful reconciliation/audit tool.
 */
public final class LedgerEntry {

    private final String entryId;
    private final String transactionId;
    private final String accountId;
    private final EntryDirection direction;
    private final Money amount;
    private final long balanceAfterMinorUnits;
    private final Instant createdAt;

    public LedgerEntry(String transactionId, String accountId, EntryDirection direction,
                        Money amount, long balanceAfterMinorUnits, Instant createdAt) {
        this.entryId = "led_" + UUID.randomUUID();
        this.transactionId = Objects.requireNonNull(transactionId);
        this.accountId = Objects.requireNonNull(accountId);
        this.direction = Objects.requireNonNull(direction);
        this.amount = Objects.requireNonNull(amount);
        this.balanceAfterMinorUnits = balanceAfterMinorUnits;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public String entryId() {
        return entryId;
    }

    public String transactionId() {
        return transactionId;
    }

    public String accountId() {
        return accountId;
    }

    public EntryDirection direction() {
        return direction;
    }

    public Money amount() {
        return amount;
    }

    public long balanceAfterMinorUnits() {
        return balanceAfterMinorUnits;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Signed contribution of this entry toward the account's balance: +amount for CREDIT, -amount for DEBIT. */
    public long signedAmountMinorUnits() {
        return direction == EntryDirection.CREDIT ? amount.minorUnits() : -amount.minorUnits();
    }

    @Override
    public String toString() {
        return "LedgerEntry{txn=%s, acct=%s, %s %s, balanceAfter=%d}"
                .formatted(transactionId, accountId, direction, amount, balanceAfterMinorUnits);
    }
}
