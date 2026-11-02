package lld.digitalwallet.service;

import lld.digitalwallet.dto.TransferRequest;
import lld.digitalwallet.dto.TransferResult;
import lld.digitalwallet.model.Account;
import lld.digitalwallet.model.KycTier;
import lld.digitalwallet.model.LedgerEntry;
import lld.digitalwallet.model.Money;

import java.time.Instant;
import java.util.Currency;
import java.util.List;

/**
 * Public API of the wallet domain. Every mutating method takes an explicit
 * {@code idempotencyKey} - this is a deliberate API design choice (not an
 * afterthought) so that no caller can accidentally invoke a mutating
 * operation without thinking about retry-safety.
 */
public interface WalletService {

    Account openAccount(String ownerId, Currency currency, KycTier kycTier);

    Money getBalance(String accountId);

    /** External funding in (e.g. linked bank top-up). Idempotent on {@code idempotencyKey}. */
    TransferResult deposit(String accountId, Money amount, String idempotencyKey, String description);

    /** External funding out (e.g. cash-out to bank). Idempotent on {@code idempotencyKey}. */
    TransferResult withdraw(String accountId, Money amount, String idempotencyKey, String description);

    /** Atomic wallet-to-wallet transfer: debit source, credit destination, write a two-entry ledger posting,
     *  all-or-nothing. Idempotent on {@link TransferRequest#idempotencyKey()}. */
    TransferResult transfer(TransferRequest request);

    /** Reverses a previously COMPLETED transaction (deposit, withdrawal, or transfer) by posting a new,
     *  compensating transaction - the original record is never mutated. Idempotent on {@code idempotencyKey}. */
    TransferResult reverse(String originalTransactionId, String idempotencyKey);

    /** Account statement: all ledger postings for the account in [from, to), oldest first. */
    List<LedgerEntry> getStatement(String accountId, Instant from, Instant to);
}
