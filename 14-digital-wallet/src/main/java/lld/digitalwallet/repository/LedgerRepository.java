package lld.digitalwallet.repository;

import lld.digitalwallet.model.LedgerEntry;

import java.time.Instant;
import java.util.List;

/** Persistence abstraction for the append-only {@link LedgerEntry} postings that back account statements. */
public interface LedgerRepository {

    void append(LedgerEntry entry);

    /** Returns all entries for an account between [from, to), ordered oldest-first. */
    List<LedgerEntry> findByAccountId(String accountId, Instant from, Instant to);

    List<LedgerEntry> findByTransactionId(String transactionId);
}
