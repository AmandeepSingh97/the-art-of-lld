package lld.vendingmachine.domain;

import lld.vendingmachine.change.GreedyChangeMaker;
import lld.vendingmachine.exceptions.InsufficientChangeException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CashBoxTest {

    @Test
    void depositThenWithdrawForChangeRoundTrips() throws InsufficientChangeException {
        CashBox box = new CashBox();
        box.deposit(List.of(Denomination.ONE_DOLLAR));
        Map<Denomination, Integer> change = box.withdrawForChange(Money.ofCents(100), new GreedyChangeMaker());
        assertEquals(1, change.get(Denomination.ONE_DOLLAR));
        assertEquals(Money.ZERO, box.totalValue());
    }

    @Test
    void withdrawForChangeFailsAtomicallyWithoutMutatingCountsOnFailure() {
        CashBox box = new CashBox();
        box.load(Denomination.QUARTER, 1); // only 25 cents available, cannot make 30 cents change
        assertThrows(InsufficientChangeException.class,
                () -> box.withdrawForChange(Money.ofCents(30), new GreedyChangeMaker()));
        // Failure must not have deducted anything.
        assertEquals(Money.ofCents(25), box.totalValue());
    }

    @Test
    void reverseDepositUndoesADeposit() {
        CashBox box = new CashBox();
        List<Denomination> inserted = List.of(Denomination.ONE_DOLLAR, Denomination.QUARTER);
        box.deposit(inserted);
        assertEquals(Money.ofCents(125), box.totalValue());
        box.reverseDeposit(inserted);
        assertEquals(Money.ZERO, box.totalValue());
    }

    @Test
    void collectExceptLeavesConfiguredFloatBehind() {
        CashBox box = new CashBox();
        box.load(Denomination.QUARTER, 10);
        box.load(Denomination.ONE_DOLLAR, 5);
        Map<Denomination, Integer> collected = box.collectExcept(Map.of(Denomination.QUARTER, 4));
        assertEquals(6, collected.get(Denomination.QUARTER));
        assertEquals(5, collected.get(Denomination.ONE_DOLLAR));
        assertEquals(Money.ofCents(100), box.totalValue()); // 4 quarters left behind
    }

    /**
     * Concurrency correctness test: many threads race to withdraw change for the
     * same small float. Exactly as many should succeed as the float can support;
     * none should observe a negative or over-drawn count, which would indicate a
     * lost-update race inside the lock-guarded compound operation.
     */
    @Test
    void concurrentWithdrawalsNeverOverdrawTheFloat() throws InterruptedException {
        CashBox box = new CashBox();
        box.load(Denomination.ONE_DOLLAR, 10); // exactly 10 x $1 available

        int threadCount = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    box.withdrawForChange(Money.ofCents(100), new GreedyChangeMaker());
                    successCount.incrementAndGet();
                } catch (InsufficientChangeException e) {
                    failureCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertEquals(true, pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(10, successCount.get());
        assertEquals(threadCount - 10, failureCount.get());
        assertEquals(Money.ZERO, box.totalValue());
    }
}
