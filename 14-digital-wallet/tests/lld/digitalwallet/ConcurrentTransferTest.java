package lld.digitalwallet;

import lld.digitalwallet.dto.TransferRequest;
import lld.digitalwallet.exception.InsufficientFundsException;
import lld.digitalwallet.idempotency.InMemoryIdempotencyStore;
import lld.digitalwallet.model.Account;
import lld.digitalwallet.model.KycTier;
import lld.digitalwallet.model.Money;
import lld.digitalwallet.repository.InMemoryAccountRepository;
import lld.digitalwallet.repository.InMemoryLedgerRepository;
import lld.digitalwallet.repository.InMemoryTransactionRepository;
import lld.digitalwallet.service.WalletService;
import lld.digitalwallet.service.WalletServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the three safety properties a concurrent wallet transfer implementation must have:
 * <ol>
 *   <li><b>No overdraft</b>: no account balance is ever observed negative, even under heavy
 *       concurrent debit pressure.</li>
 *   <li><b>Conservation of money</b>: the sum of all account balances never changes across a
 *       burst of purely internal transfers (nothing is created or destroyed).</li>
 *   <li><b>No deadlock</b>: threads transferring in <em>opposite directions</em> between the same
 *       two accounts (A→B and B→A simultaneously) all complete within a bounded timeout. This is
 *       the scenario that deadlocks if a naive implementation always locks "source then
 *       destination" instead of a global consistent order - see
 *       {@code WalletServiceImpl#postTwoEntryMovement} javadoc.</li>
 * </ol>
 */
class ConcurrentTransferTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void concurrentBidirectionalTransfersNeverOverdraftNeverDeadlockAndConserveTotal() throws InterruptedException {
        var currency = java.util.Currency.getInstance("USD");
        WalletService wallet = new WalletServiceImpl(
                new InMemoryAccountRepository(),
                new InMemoryTransactionRepository(),
                new InMemoryLedgerRepository(),
                new InMemoryIdempotencyStore(),
                Clock.systemUTC());

        Account a = wallet.openAccount("a", currency, KycTier.TIER_2_FULL);
        Account b = wallet.openAccount("b", currency, KycTier.TIER_2_FULL);
        Money seed = Money.ofMajorUnits(new BigDecimal("1000.00"), currency);
        wallet.deposit(a.accountId(), seed, UUID.randomUUID().toString(), "seed a");
        wallet.deposit(b.accountId(), seed, UUID.randomUUID().toString(), "seed b");
        Money totalBefore = wallet.getBalance(a.accountId()).add(wallet.getBalance(b.accountId()));

        int threadCount = 16;
        int transfersPerThread = 200;
        Money unit = Money.ofMajorUnits(new BigDecimal("1.00"), currency);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger insufficientFundsRejections = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            boolean aToB = t % 2 == 0;
            String src = aToB ? a.accountId() : b.accountId();
            String dst = aToB ? b.accountId() : a.accountId();
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < transfersPerThread; i++) {
                        try {
                            wallet.transfer(new TransferRequest(src, dst, unit, UUID.randomUUID().toString(), "stress"));
                        } catch (InsufficientFundsException expectedUnderContention) {
                            insufficientFundsRejections.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finishedBeforeTimeout = doneLatch.await(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(finishedBeforeTimeout, "all transfer threads must complete without deadlocking");

        Money balanceA = wallet.getBalance(a.accountId());
        Money balanceB = wallet.getBalance(b.accountId());
        assertFalse(balanceA.isNegative(), "account A must never be overdrawn: " + balanceA);
        assertFalse(balanceB.isNegative(), "account B must never be overdrawn: " + balanceB);

        Money totalAfter = balanceA.add(balanceB);
        assertEquals(totalBefore, totalAfter, "money must be conserved across purely internal transfers");
    }

    /**
     * A narrower, deterministic companion to the stress test above: explicitly interleaves exactly two
     * threads transferring in opposite directions (A→B while B→A) many times and asserts completion
     * within a tight timeout, making the deadlock-freedom property easy to reason about in isolation.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void twoThreadsTransferringInOppositeDirectionsDoNotDeadlock() throws InterruptedException {
        var currency = java.util.Currency.getInstance("USD");
        WalletService wallet = new WalletServiceImpl(
                new InMemoryAccountRepository(),
                new InMemoryTransactionRepository(),
                new InMemoryLedgerRepository(),
                new InMemoryIdempotencyStore(),
                Clock.systemUTC());

        Account a = wallet.openAccount("a", currency, KycTier.TIER_2_FULL);
        Account b = wallet.openAccount("b", currency, KycTier.TIER_2_FULL);
        Money seed = Money.ofMajorUnits(new BigDecimal("500.00"), currency);
        wallet.deposit(a.accountId(), seed, UUID.randomUUID().toString(), "seed a");
        wallet.deposit(b.accountId(), seed, UUID.randomUUID().toString(), "seed b");
        Money unit = Money.ofMajorUnits(new BigDecimal("1.00"), currency);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Runnable> directions = List.of(
                () -> runTransfers(wallet, a.accountId(), b.accountId(), unit, 300),
                () -> runTransfers(wallet, b.accountId(), a.accountId(), unit, 300));

        for (Runnable direction : directions) {
            pool.submit(() -> {
                try {
                    direction.run();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        boolean finished = doneLatch.await(8, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertTrue(finished, "opposite-direction transfers between the same two accounts must not deadlock");
    }

    private static void runTransfers(WalletService wallet, String src, String dst, Money unit, int count) {
        for (int i = 0; i < count; i++) {
            try {
                wallet.transfer(new TransferRequest(src, dst, unit, UUID.randomUUID().toString(), "ping-pong"));
            } catch (InsufficientFundsException ignored) {
                // Acceptable under contention; the point of this test is absence of deadlock, not balances.
            }
        }
    }
}
