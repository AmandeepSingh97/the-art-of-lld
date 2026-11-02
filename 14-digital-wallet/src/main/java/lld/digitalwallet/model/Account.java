package lld.digitalwallet.model;

import lld.digitalwallet.exception.AccountNotActiveException;
import lld.digitalwallet.exception.InsufficientFundsException;
import lld.digitalwallet.exception.TransferLimitExceededException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A wallet account: the mutable aggregate root whose balance is the thing we
 * must protect from concurrent corruption.
 *
 * <h2>Concurrency design</h2>
 * Every account owns a private {@link ReentrantLock}. All balance mutations
 * ({@link #applyDebit}, {@link #applyCredit}) acquire this lock. The lock is
 * exposed via {@link #lock()} so a service orchestrating a two-account
 * transfer can hold <em>both</em> locks for the duration of the whole
 * check-then-act sequence (debit source + credit destination + ledger
 * write), making the transfer atomic with respect to any other thread
 * touching either account.
 *
 * <p>Using a single lock per account (pessimistic, exclusive) rather than,
 * say, an {@link java.util.concurrent.atomic.AtomicLong} for the balance is a
 * deliberate choice: a transfer is a multi-step invariant check
 * ("does source have enough funds?") followed by two writes that must be
 * seen atomically together. A lock-free CAS loop only protects a single
 * field update, not a read-check-then-two-writes sequence across two
 * objects, so we need a mutex here. See STAFF_LEVEL_DEEP_DIVE.md for the
 * optimistic-locking (version column) alternative used in the persistent
 * store design.
 *
 * <p>{@link #balanceMinorUnits} is additionally {@code volatile} so that a
 * read-only caller (e.g. {@link #getBalanceSnapshot()} used by a dashboard
 * poller) observes the latest committed value without needing to acquire the
 * lock itself, at the cost of possibly reading a value that changes a moment
 * later (acceptable for a non-authoritative read; any mutation always goes
 * through the lock).
 */
public final class Account {

    private final String accountId;
    private final String ownerId;
    private final Currency currency;
    private final ReentrantLock lock = new ReentrantLock();
    private final Clock clock;

    private volatile long balanceMinorUnits;
    private volatile AccountStatus status;
    private volatile KycTier kycTier;

    // Monotonically increasing mutation counter. Not used for in-memory
    // concurrency control (the lock already gives us that) but modelled here
    // because it is exactly the "version" column a persistent optimistic-
    // locking implementation would use (see STAFF_LEVEL_DEEP_DIVE.md).
    private long version;

    // Lightweight daily-limit bookkeeping (guarded by `lock`).
    private long dailyTransferredMinorUnits;
    private LocalDate limitWindowDate;

    public Account(String accountId, String ownerId, Currency currency, KycTier kycTier, Clock clock) {
        this.accountId = Objects.requireNonNull(accountId);
        this.ownerId = Objects.requireNonNull(ownerId);
        this.currency = Objects.requireNonNull(currency);
        this.kycTier = Objects.requireNonNull(kycTier);
        this.clock = Objects.requireNonNull(clock);
        this.status = AccountStatus.ACTIVE;
        this.balanceMinorUnits = 0L;
        this.version = 0L;
        this.limitWindowDate = LocalDate.now(clock);
        this.dailyTransferredMinorUnits = 0L;
    }

    public String accountId() {
        return accountId;
    }

    public String ownerId() {
        return ownerId;
    }

    public Currency currency() {
        return currency;
    }

    public AccountStatus status() {
        return status;
    }

    public KycTier kycTier() {
        return kycTier;
    }

    /** Exposed so a caller can hold both locks (in a fixed, deadlock-free order) across a multi-account operation. */
    public ReentrantLock lock() {
        return lock;
    }

    /** Non-authoritative snapshot read; does not acquire the lock. See class javadoc. */
    public Money getBalanceSnapshot() {
        return Money.ofMinorUnits(balanceMinorUnits, currency);
    }

    public long version() {
        return version;
    }

    public void freeze() {
        lock.lock();
        try {
            status = AccountStatus.FROZEN;
        } finally {
            lock.unlock();
        }
    }

    public void unfreeze() {
        lock.lock();
        try {
            if (status == AccountStatus.FROZEN) {
                status = AccountStatus.ACTIVE;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Debits the account by {@code amount}. Precondition/postcondition: the
     * account's lock is reentrant so this is safe to call both standalone
     * and while the caller already holds the lock (e.g. from within a
     * transfer orchestration that pre-acquired it).
     *
     * @throws InsufficientFundsException if the resulting balance would go negative
     * @throws AccountNotActiveException  if the account is frozen/closed
     */
    public void applyDebit(Money amount, boolean enforceDailyLimit) {
        requireSameCurrency(amount);
        lock.lock();
        try {
            requireActive();
            if (balanceMinorUnits < amount.minorUnits()) {
                throw new InsufficientFundsException(accountId, amount, getBalanceSnapshot());
            }
            if (enforceDailyLimit) {
                resetDailyWindowIfNeeded();
                long projected = dailyTransferredMinorUnits + amount.minorUnits();
                if (projected > kycTier.dailyTransferLimitMinorUnits()) {
                    throw new TransferLimitExceededException(accountId, kycTier, projected);
                }
                dailyTransferredMinorUnits = projected;
            }
            balanceMinorUnits -= amount.minorUnits();
            version++;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Credits the account by {@code amount}.
     *
     * @throws AccountNotActiveException if the account is closed (frozen accounts may still receive credits,
     *                                    mirroring real wallets where incoming funds are accepted but outgoing
     *                                    transfers are blocked during a freeze)
     */
    public void applyCredit(Money amount) {
        requireSameCurrency(amount);
        lock.lock();
        try {
            if (status == AccountStatus.CLOSED) {
                throw new AccountNotActiveException(accountId, status);
            }
            balanceMinorUnits += amount.minorUnits();
            version++;
        } finally {
            lock.unlock();
        }
    }

    /** Reverts a daily-limit reservation made by a debit that is later reversed/refunded. Must be called while holding `lock`. */
    public void releaseDailyLimitReservation(Money amount) {
        lock.lock();
        try {
            resetDailyWindowIfNeeded();
            dailyTransferredMinorUnits = Math.max(0L, dailyTransferredMinorUnits - amount.minorUnits());
        } finally {
            lock.unlock();
        }
    }

    private void resetDailyWindowIfNeeded() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(limitWindowDate)) {
            limitWindowDate = today;
            dailyTransferredMinorUnits = 0L;
        }
    }

    private void requireActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(accountId, status);
        }
    }

    private void requireSameCurrency(Money amount) {
        if (!amount.currency().equals(currency)) {
            throw new IllegalArgumentException(
                    "Account " + accountId + " is in " + currency + " but amount is in " + amount.currency());
        }
    }

    public static String newAccountId() {
        return "acc_" + UUID.randomUUID();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Account account)) return false;
        return accountId.equals(account.accountId);
    }

    @Override
    public int hashCode() {
        return accountId.hashCode();
    }

    @Override
    public String toString() {
        return "Account{id=%s, owner=%s, balance=%s, status=%s}"
                .formatted(accountId, ownerId, getBalanceSnapshot(), status);
    }
}
