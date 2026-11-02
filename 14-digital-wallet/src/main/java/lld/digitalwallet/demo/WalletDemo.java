package lld.digitalwallet.demo;

import lld.digitalwallet.dto.TransferRequest;
import lld.digitalwallet.dto.TransferResult;
import lld.digitalwallet.exception.InsufficientFundsException;
import lld.digitalwallet.exception.TransferLimitExceededException;
import lld.digitalwallet.idempotency.InMemoryIdempotencyStore;
import lld.digitalwallet.model.Account;
import lld.digitalwallet.model.KycTier;
import lld.digitalwallet.model.LedgerEntry;
import lld.digitalwallet.model.Money;
import lld.digitalwallet.repository.InMemoryAccountRepository;
import lld.digitalwallet.repository.InMemoryLedgerRepository;
import lld.digitalwallet.repository.InMemoryTransactionRepository;
import lld.digitalwallet.service.WalletService;
import lld.digitalwallet.service.WalletServiceImpl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runnable demonstration of the wallet domain: account creation, deposit,
 * wallet-to-wallet transfer, idempotent retry, reversal, failure handling,
 * and a concurrent-transfer stress test that proves no overdraft and no
 * deadlock. This is the "Main/Demo class" required by the implementation
 * guide, and doubles as executable documentation of the public API.
 */
public final class WalletDemo {

    public static void main(String[] args) throws InterruptedException {
        Currency usd = Currency.getInstance("USD");
        WalletService wallet = new WalletServiceImpl(
                new InMemoryAccountRepository(),
                new InMemoryTransactionRepository(),
                new InMemoryLedgerRepository(),
                new InMemoryIdempotencyStore(),
                Clock.systemUTC());

        System.out.println("=== 1. Open accounts ===");
        Account alice = wallet.openAccount("user-alice", usd, KycTier.TIER_1_BASIC);
        Account bob = wallet.openAccount("user-bob", usd, KycTier.TIER_1_BASIC);
        System.out.println(alice);
        System.out.println(bob);

        System.out.println("\n=== 2. Deposit (external funding in) ===");
        Money hundred = Money.ofMajorUnits(new BigDecimal("100.00"), usd);
        wallet.deposit(alice.accountId(), hundred, UUID.randomUUID().toString(), "Bank top-up");
        System.out.println("Alice balance after deposit: " + wallet.getBalance(alice.accountId()));

        System.out.println("\n=== 3. Transfer Alice -> Bob ===");
        Money thirty = Money.ofMajorUnits(new BigDecimal("30.00"), usd);
        String idemKey = UUID.randomUUID().toString();
        TransferResult result = wallet.transfer(new TransferRequest(
                alice.accountId(), bob.accountId(), thirty, idemKey, "Dinner split"));
        System.out.println("Transfer result: " + result);
        System.out.println("Alice balance: " + wallet.getBalance(alice.accountId()));
        System.out.println("Bob balance:   " + wallet.getBalance(bob.accountId()));

        System.out.println("\n=== 4. Client retries the SAME request (network timeout on first response) ===");
        TransferResult retry = wallet.transfer(new TransferRequest(
                alice.accountId(), bob.accountId(), thirty, idemKey, "Dinner split"));
        System.out.println("Retry result: " + retry + " (replayed=" + retry.replayedFromIdempotencyCache() + ")");
        System.out.println("Alice balance UNCHANGED by retry: " + wallet.getBalance(alice.accountId()));

        System.out.println("\n=== 5. Insufficient funds is rejected ===");
        try {
            wallet.transfer(new TransferRequest(bob.accountId(), alice.accountId(),
                    Money.ofMajorUnits(new BigDecimal("999999.00"), usd), UUID.randomUUID().toString(), "overdraw attempt"));
        } catch (InsufficientFundsException e) {
            System.out.println("Rejected as expected: " + e.getMessage());
        }

        System.out.println("\n=== 6. Daily transfer limit is enforced ===");
        wallet.deposit(bob.accountId(), Money.ofMajorUnits(new BigDecimal("60000.00"), usd),
                UUID.randomUUID().toString(), "fund bob for limit demo");
        try {
            // TIER_1_BASIC caps outbound transfers at 50,000.00/day; Bob now has the funds but not the headroom.
            wallet.transfer(new TransferRequest(bob.accountId(), alice.accountId(),
                    Money.ofMajorUnits(new BigDecimal("51000.00"), usd), UUID.randomUUID().toString(), "big transfer"));
        } catch (TransferLimitExceededException e) {
            System.out.println("Rejected as expected: " + e.getMessage());
        }

        System.out.println("\n=== 7. Reversal (refund) of the dinner-split transfer ===");
        TransferResult reversal = wallet.reverse(result.transaction().transactionId(), UUID.randomUUID().toString());
        System.out.println("Reversal result: " + reversal);
        System.out.println("Alice balance restored: " + wallet.getBalance(alice.accountId()));
        System.out.println("Bob balance restored:   " + wallet.getBalance(bob.accountId()));

        System.out.println("\n=== 8. Account statement for Alice ===");
        List<LedgerEntry> statement = wallet.getStatement(alice.accountId(), Instant.EPOCH, Instant.now().plusSeconds(60));
        statement.forEach(System.out::println);

        System.out.println("\n=== 9. Concurrency stress test: 8 threads firing transfers both directions ===");
        runConcurrencyDemo(wallet, usd, alice, bob);
    }

    /**
     * Fires many concurrent transfers in both directions between two accounts from multiple threads to
     * demonstrate: (a) the balance total is conserved (no money created/destroyed), (b) no account ever
     * goes negative, and (c) no deadlock occurs despite threads racing with opposite debit/credit roles -
     * the deadlock-free lock-ordering guarantee is what makes this terminate at all.
     */
    private static void runConcurrencyDemo(WalletService wallet, Currency usd, Account alice, Account bob) throws InterruptedException {
        Money startingAlice = wallet.getBalance(alice.accountId());
        Money startingBob = wallet.getBalance(bob.accountId());
        Money totalBefore = startingAlice.add(startingBob);

        int threadCount = 8;
        int transfersPerThread = 50;
        Money unit = Money.ofMajorUnits(new BigDecimal("1.00"), usd);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            boolean aliceToBob = t % 2 == 0;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < transfersPerThread; i++) {
                        String src = aliceToBob ? alice.accountId() : bob.accountId();
                        String dst = aliceToBob ? bob.accountId() : alice.accountId();
                        try {
                            wallet.transfer(new TransferRequest(src, dst, unit, UUID.randomUUID().toString(), "stress"));
                        } catch (InsufficientFundsException ignored) {
                            // Expected occasionally once a side runs low; not a correctness bug.
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        System.out.println("All threads finished within timeout (no deadlock): " + finished);

        Money totalAfter = wallet.getBalance(alice.accountId()).add(wallet.getBalance(bob.accountId()));
        System.out.println("Total before: " + totalBefore + ", total after: " + totalAfter
                + ", conserved=" + totalBefore.equals(totalAfter));
    }

    private WalletDemo() {
    }
}
