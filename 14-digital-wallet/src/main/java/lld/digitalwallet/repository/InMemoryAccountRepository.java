package lld.digitalwallet.repository;

import lld.digitalwallet.model.Account;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory {@link AccountRepository}. {@link ConcurrentHashMap}
 * is sufficient here because the map only ever stores/retrieves whole
 * {@link Account} references (identity, not state) - all state mutation
 * safety is the {@link Account}'s own lock's responsibility, not the map's.
 * We never need check-then-act across two different accounts at the
 * repository level (that happens in the service layer with explicit
 * lock ordering), so plain {@code ConcurrentHashMap} get/put/putIfAbsent is
 * enough; we don't need the map itself to serialize anything beyond
 * single-key visibility.
 */
public final class InMemoryAccountRepository implements AccountRepository {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    @Override
    public Account save(Account account) {
        accounts.put(account.accountId(), account);
        return account;
    }

    @Override
    public Optional<Account> findById(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    @Override
    public boolean existsById(String accountId) {
        return accounts.containsKey(accountId);
    }
}
