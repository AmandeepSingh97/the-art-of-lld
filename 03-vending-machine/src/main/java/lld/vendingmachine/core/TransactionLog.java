package lld.vendingmachine.core;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, append-only in-memory audit trail of completed transactions.
 * {@link CopyOnWriteArrayList} is a deliberate choice: writes (one per
 * completed purchase) are rare relative to reads (an admin dashboard polling
 * the log), and we want iteration/reads to never block on a writer or throw
 * {@code ConcurrentModificationException}.
 */
public final class TransactionLog {

    private final List<Transaction> transactions = new CopyOnWriteArrayList<>();
    private final AtomicLong idGenerator = new AtomicLong();

    public String nextTransactionId() {
        return "TXN-" + idGenerator.incrementAndGet();
    }

    public void record(Transaction transaction) {
        transactions.add(transaction);
    }

    public List<Transaction> all() {
        return Collections.unmodifiableList(transactions);
    }
}
