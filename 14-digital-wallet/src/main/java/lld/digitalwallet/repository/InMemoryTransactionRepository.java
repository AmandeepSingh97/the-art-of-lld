package lld.digitalwallet.repository;

import lld.digitalwallet.model.Transaction;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link TransactionRepository}. Note the idempotency-key index is
 * a <em>secondary</em>, best-effort lookup table here (for "replay my last
 * request by key" style queries); the actual exactly-once guarantee for
 * concurrent duplicate submissions is enforced by
 * {@link lld.digitalwallet.idempotency.IdempotencyStore}, not by this class -
 * see its javadoc for why the atomicity has to live there instead of here.
 */
public final class InMemoryTransactionRepository implements TransactionRepository {

    private final Map<String, Transaction> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyKeyToTransactionId = new ConcurrentHashMap<>();

    @Override
    public Transaction save(Transaction transaction) {
        byId.put(transaction.transactionId(), transaction);
        idempotencyKeyToTransactionId.put(transaction.idempotencyKey(), transaction.transactionId());
        return transaction;
    }

    @Override
    public Optional<Transaction> findById(String transactionId) {
        return Optional.ofNullable(byId.get(transactionId));
    }

    @Override
    public Optional<Transaction> findByIdempotencyKey(String idempotencyKey) {
        String txnId = idempotencyKeyToTransactionId.get(idempotencyKey);
        return txnId == null ? Optional.empty() : findById(txnId);
    }
}
