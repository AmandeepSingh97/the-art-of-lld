package lld.digitalwallet.repository;

import lld.digitalwallet.model.Transaction;

import java.util.Optional;

/** Persistence abstraction for {@link Transaction} records (the client-facing "what did I ask for" record,
 *  distinct from the {@link lld.digitalwallet.model.LedgerEntry} postings - see {@link LedgerRepository}). */
public interface TransactionRepository {

    Transaction save(Transaction transaction);

    Optional<Transaction> findById(String transactionId);

    /** Looks up a transaction previously stored under the given client idempotency key, if any. */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
