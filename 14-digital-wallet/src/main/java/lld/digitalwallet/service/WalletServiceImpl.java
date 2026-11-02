package lld.digitalwallet.service;

import lld.digitalwallet.dto.TransferRequest;
import lld.digitalwallet.dto.TransferResult;
import lld.digitalwallet.exception.IllegalTransactionStateException;
import lld.digitalwallet.exception.InvalidRequestException;
import lld.digitalwallet.exception.TransactionNotFoundException;
import lld.digitalwallet.idempotency.IdempotencyStore;
import lld.digitalwallet.model.Account;
import lld.digitalwallet.model.EntryDirection;
import lld.digitalwallet.model.KycTier;
import lld.digitalwallet.model.LedgerEntry;
import lld.digitalwallet.model.Money;
import lld.digitalwallet.model.Transaction;
import lld.digitalwallet.model.TransactionStatus;
import lld.digitalwallet.model.TransactionType;
import lld.digitalwallet.repository.AccountRepository;
import lld.digitalwallet.repository.LedgerRepository;
import lld.digitalwallet.repository.TransactionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core wallet orchestration: locking strategy, idempotency, double-entry
 * ledger writes, daily-limit enforcement, and reversal all live here. See
 * README.md section 11 for the full concurrency argument; the short version:
 *
 * <ul>
 *   <li><b>Single-account ops</b> (deposit/withdraw) only ever touch one
 *       {@link Account}'s lock - no ordering concern.</li>
 *   <li><b>Two-account ops</b> (transfer, transfer-shaped reversal) always
 *       acquire both accounts' locks in a single, globally consistent order
 *       (ascending {@code accountId}) regardless of which account is the
 *       debit side and which is the credit side. Two threads transferring
 *       A→B and B→A concurrently therefore always agree on which lock to
 *       take first, which is precisely what prevents the classic
 *       dining-philosophers-style deadlock of "thread 1 holds A, wants B;
 *       thread 2 holds B, wants A".</li>
 *   <li><b>Idempotency</b> wraps the entire locked critical section, so a
 *       duplicate request with the same key never re-enters the locking
 *       logic at all - it is answered from cache before any lock is
 *       touched.</li>
 * </ul>
 */
public final class WalletServiceImpl implements WalletService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    public WalletServiceImpl(AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              LedgerRepository ledgerRepository,
                              IdempotencyStore idempotencyStore,
                              Clock clock) {
        this.accountRepository = Objects.requireNonNull(accountRepository);
        this.transactionRepository = Objects.requireNonNull(transactionRepository);
        this.ledgerRepository = Objects.requireNonNull(ledgerRepository);
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Account openAccount(String ownerId, Currency currency, KycTier kycTier) {
        Account account = new Account(Account.newAccountId(), ownerId, currency, kycTier, clock);
        return accountRepository.save(account);
    }

    @Override
    public Money getBalance(String accountId) {
        return accountRepository.getOrThrow(accountId).getBalanceSnapshot();
    }

    @Override
    public TransferResult deposit(String accountId, Money amount, String idempotencyKey, String description) {
        validatePositiveAmount(amount);
        validateIdempotencyKey(idempotencyKey);
        boolean[] freshlyComputed = {false};
        Transaction txn = idempotencyStore.computeIfAbsent(idempotencyKey, () -> {
            freshlyComputed[0] = true;
            return doSingleEntryMovement(TransactionType.DEPOSIT, accountId, amount, true,
                    idempotencyKey, description, null, false);
        });
        return new TransferResult(txn, !freshlyComputed[0]);
    }

    @Override
    public TransferResult withdraw(String accountId, Money amount, String idempotencyKey, String description) {
        validatePositiveAmount(amount);
        validateIdempotencyKey(idempotencyKey);
        boolean[] freshlyComputed = {false};
        Transaction txn = idempotencyStore.computeIfAbsent(idempotencyKey, () -> {
            freshlyComputed[0] = true;
            return doSingleEntryMovement(TransactionType.WITHDRAWAL, accountId, amount, false,
                    idempotencyKey, description, null, true);
        });
        return new TransferResult(txn, !freshlyComputed[0]);
    }

    @Override
    public TransferResult transfer(TransferRequest request) {
        validatePositiveAmount(request.amount());
        validateIdempotencyKey(request.idempotencyKey());
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new InvalidRequestException("Source and destination accounts must differ: " + request.sourceAccountId());
        }
        boolean[] freshlyComputed = {false};
        Transaction txn = idempotencyStore.computeIfAbsent(request.idempotencyKey(), () -> {
            freshlyComputed[0] = true;
            return postTwoEntryMovement(TransactionType.TRANSFER, request.sourceAccountId(),
                    request.destinationAccountId(), request.amount(), request.idempotencyKey(),
                    request.description(), null, true);
        });
        return new TransferResult(txn, !freshlyComputed[0]);
    }

    @Override
    public TransferResult reverse(String originalTransactionId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        boolean[] freshlyComputed = {false};
        Transaction txn = idempotencyStore.computeIfAbsent(idempotencyKey, () -> {
            freshlyComputed[0] = true;
            return doReverse(originalTransactionId, idempotencyKey);
        });
        return new TransferResult(txn, !freshlyComputed[0]);
    }

    @Override
    public List<LedgerEntry> getStatement(String accountId, Instant from, Instant to) {
        accountRepository.getOrThrow(accountId); // 404s early if the account doesn't exist
        return ledgerRepository.findByAccountId(accountId, from, to);
    }

    // ---------------------------------------------------------------- //
    // Internal orchestration
    // ---------------------------------------------------------------- //

    private Transaction doReverse(String originalTransactionId, String reversalIdempotencyKey) {
        Transaction original = transactionRepository.findById(originalTransactionId)
                .orElseThrow(() -> new TransactionNotFoundException(originalTransactionId));
        if (original.status() != TransactionStatus.COMPLETED) {
            throw new IllegalTransactionStateException(
                    "Transaction " + originalTransactionId + " cannot be reversed from status " + original.status());
        }

        Transaction reversal;
        String reversalDescription = "Reversal of " + originalTransactionId;
        switch (original.type()) {
            case TRANSFER -> {
                String originallyDebitedAccount = original.sourceAccountId().orElseThrow();
                String originallyCreditedAccount = original.destinationAccountId().orElseThrow();
                // Reverse direction: debit what was credited, credit what was debited.
                reversal = postTwoEntryMovement(TransactionType.REVERSAL, originallyCreditedAccount,
                        originallyDebitedAccount, original.amount(), reversalIdempotencyKey, reversalDescription,
                        originalTransactionId, false);
                releaseDailyLimitReservation(originallyDebitedAccount, original.amount());
            }
            case DEPOSIT -> {
                String accountId = original.destinationAccountId().orElseThrow();
                reversal = doSingleEntryMovement(TransactionType.REVERSAL, accountId, original.amount(), false,
                        reversalIdempotencyKey, reversalDescription, originalTransactionId, false);
            }
            case WITHDRAWAL -> {
                String accountId = original.sourceAccountId().orElseThrow();
                reversal = doSingleEntryMovement(TransactionType.REVERSAL, accountId, original.amount(), true,
                        reversalIdempotencyKey, reversalDescription, originalTransactionId, false);
                releaseDailyLimitReservation(accountId, original.amount());
            }
            case REVERSAL -> throw new IllegalTransactionStateException(
                    "Cannot reverse a REVERSAL transaction: " + originalTransactionId);
            default -> throw new IllegalStateException("Unhandled transaction type " + original.type());
        }

        transactionRepository.save(original.withStatus(TransactionStatus.REVERSED));
        return reversal;
    }

    /**
     * Executes a single-account credit or debit inside that account's lock, then persists the
     * {@link Transaction} record and its one {@link LedgerEntry}. Used for deposit, withdrawal, and the
     * reversal of either.
     */
    private Transaction doSingleEntryMovement(TransactionType type, String accountId, Money amount, boolean isCredit,
                                               String idempotencyKey, String description, String reversalOf,
                                               boolean enforceDailyLimit) {
        Account account = accountRepository.getOrThrow(accountId);
        ReentrantLock lock = account.lock();
        lock.lock();
        try {
            requireMatchingCurrency(account, amount);
            if (isCredit) {
                account.applyCredit(amount);
            } else {
                account.applyDebit(amount, enforceDailyLimit);
            }

            String transactionId = Transaction.newTransactionId();
            Instant now = clock.instant();
            Transaction.Builder builder = Transaction.builder()
                    .transactionId(transactionId)
                    .type(type)
                    .amount(amount)
                    .status(TransactionStatus.COMPLETED)
                    .createdAt(now)
                    .idempotencyKey(idempotencyKey)
                    .description(description)
                    .reversalOfTransactionId(reversalOf);
            if (isCredit) {
                builder.destinationAccountId(accountId);
            } else {
                builder.sourceAccountId(accountId);
            }
            Transaction txn = builder.build();
            transactionRepository.save(txn);

            EntryDirection direction = isCredit ? EntryDirection.CREDIT : EntryDirection.DEBIT;
            ledgerRepository.append(new LedgerEntry(transactionId, accountId, direction, amount,
                    account.getBalanceSnapshot().minorUnits(), now));
            return txn;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Executes an atomic two-account movement (debit one account, credit another) with deadlock-free
     * lock ordering, then persists the {@link Transaction} and its two {@link LedgerEntry} postings.
     * Used for transfers and the reversal of a transfer.
     */
    private Transaction postTwoEntryMovement(TransactionType type, String debitAccountId, String creditAccountId,
                                              Money amount, String idempotencyKey, String description,
                                              String reversalOf, boolean enforceDailyLimit) {
        Account debitAccount = accountRepository.getOrThrow(debitAccountId);
        Account creditAccount = accountRepository.getOrThrow(creditAccountId);

        // Deadlock avoidance: always lock in a single global order (ascending accountId), independent
        // of which account happens to be the debit side vs the credit side of *this particular* call.
        Account lockFirst = debitAccount.accountId().compareTo(creditAccount.accountId()) <= 0 ? debitAccount : creditAccount;
        Account lockSecond = lockFirst == debitAccount ? creditAccount : debitAccount;

        lockFirst.lock().lock();
        try {
            lockSecond.lock().lock();
            try {
                requireMatchingCurrency(debitAccount, amount);
                requireMatchingCurrency(creditAccount, amount);

                debitAccount.applyDebit(amount, enforceDailyLimit);
                creditAccount.applyCredit(amount);

                String transactionId = Transaction.newTransactionId();
                Instant now = clock.instant();
                Transaction txn = Transaction.builder()
                        .transactionId(transactionId)
                        .type(type)
                        .sourceAccountId(debitAccountId)
                        .destinationAccountId(creditAccountId)
                        .amount(amount)
                        .status(TransactionStatus.COMPLETED)
                        .createdAt(now)
                        .idempotencyKey(idempotencyKey)
                        .description(description)
                        .reversalOfTransactionId(reversalOf)
                        .build();
                transactionRepository.save(txn);

                ledgerRepository.append(new LedgerEntry(transactionId, debitAccountId, EntryDirection.DEBIT,
                        amount, debitAccount.getBalanceSnapshot().minorUnits(), now));
                ledgerRepository.append(new LedgerEntry(transactionId, creditAccountId, EntryDirection.CREDIT,
                        amount, creditAccount.getBalanceSnapshot().minorUnits(), now));
                return txn;
            } finally {
                lockSecond.lock().unlock();
            }
        } finally {
            lockFirst.lock().unlock();
        }
    }

    private void releaseDailyLimitReservation(String accountId, Money amount) {
        accountRepository.findById(accountId).ifPresent(a -> a.releaseDailyLimitReservation(amount));
    }

    private static void requireMatchingCurrency(Account account, Money amount) {
        if (!account.currency().equals(amount.currency())) {
            throw new InvalidRequestException(
                    "Account " + account.accountId() + " is in " + account.currency() + " but amount is in " + amount.currency());
        }
    }

    private static void validatePositiveAmount(Money amount) {
        if (!amount.isPositive()) {
            throw new InvalidRequestException("Amount must be strictly positive, was: " + amount);
        }
    }

    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidRequestException("idempotencyKey must be a non-blank client-supplied key");
        }
    }
}
