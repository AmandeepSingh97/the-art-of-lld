package lld.digitalwallet.dto;

import lld.digitalwallet.model.Transaction;

/**
 * API-facing result of a mutating wallet operation. Wraps the persisted
 * {@link Transaction} plus a flag telling the caller whether this exact
 * response was replayed from the idempotency cache (useful for client-side
 * logging/metrics - "my retry was deduped" vs "this was the first attempt").
 */
public final class TransferResult {
    private final Transaction transaction;
    private final boolean replayedFromIdempotencyCache;

    public TransferResult(Transaction transaction, boolean replayedFromIdempotencyCache) {
        this.transaction = transaction;
        this.replayedFromIdempotencyCache = replayedFromIdempotencyCache;
    }

    public Transaction transaction() {
        return transaction;
    }

    public boolean replayedFromIdempotencyCache() {
        return replayedFromIdempotencyCache;
    }

    @Override
    public String toString() {
        return "TransferResult{" + transaction + ", replayed=" + replayedFromIdempotencyCache + "}";
    }
}
