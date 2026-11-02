package lld.digitalwallet.repository;

import lld.digitalwallet.model.Account;

import java.util.Optional;

/**
 * Persistence abstraction for {@link Account} aggregates. Kept deliberately
 * narrow (no generic CRUD/JPA-style repository) so the service layer only
 * depends on the operations the wallet domain actually needs - this is the
 * Repository pattern used to decouple the domain/service layer from the
 * storage technology (in-memory map today, SQL table behind a DAO tomorrow)
 * without changing a single line of {@code WalletServiceImpl}.
 */
public interface AccountRepository {

    Account save(Account account);

    Optional<Account> findById(String accountId);

    default Account getOrThrow(String accountId) {
        return findById(accountId)
                .orElseThrow(() -> new lld.digitalwallet.exception.AccountNotFoundException(accountId));
    }

    boolean existsById(String accountId);
}
