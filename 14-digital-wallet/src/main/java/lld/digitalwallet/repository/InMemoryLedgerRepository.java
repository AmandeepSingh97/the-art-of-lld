package lld.digitalwallet.repository;

import lld.digitalwallet.model.LedgerEntry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-memory, append-only {@link LedgerRepository}. {@link CopyOnWriteArrayList} is used per-account
 * because ledger writes are relatively infrequent compared to statement reads (read-heavy, append-only
 * workload is the textbook case for COW) and because appends for a given account already happen while
 * the service layer holds that account's {@link lld.digitalwallet.model.Account#lock()}, so there is no
 * extra contention introduced by the list itself - COW's O(n) copy-on-append cost is paid once per
 * transaction, not per read.
 */
public final class InMemoryLedgerRepository implements LedgerRepository {

    private final Map<String, List<LedgerEntry>> entriesByAccount = new ConcurrentHashMap<>();
    private final Map<String, List<LedgerEntry>> entriesByTransaction = new ConcurrentHashMap<>();

    @Override
    public void append(LedgerEntry entry) {
        entriesByAccount
                .computeIfAbsent(entry.accountId(), k -> new CopyOnWriteArrayList<>())
                .add(entry);
        entriesByTransaction
                .computeIfAbsent(entry.transactionId(), k -> new CopyOnWriteArrayList<>())
                .add(entry);
    }

    @Override
    public List<LedgerEntry> findByAccountId(String accountId, Instant from, Instant to) {
        return entriesByAccount.getOrDefault(accountId, List.of()).stream()
                .filter(e -> !e.createdAt().isBefore(from) && e.createdAt().isBefore(to))
                .collect(Collectors.toList());
    }

    @Override
    public List<LedgerEntry> findByTransactionId(String transactionId) {
        return List.copyOf(entriesByTransaction.getOrDefault(transactionId, List.of()));
    }
}
