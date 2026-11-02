package lld.stockmanagement.ledger;

import lld.stockmanagement.exception.InsufficientStockException;
import lld.stockmanagement.model.InventoryKey;
import lld.stockmanagement.model.InventoryLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared concurrency proof, run against every {@link InventoryLedger} strategy by
 * its concrete subclass below. This is the test that answers the core interview
 * question for this problem: "how do you prevent overselling under concurrent
 * decrement of the same SKU?"
 *
 * <p>The scenario: seed a single (sku, warehouse) row with a fixed stock level, then
 * hammer it from many threads each attempting to reserve 1 unit at a time, with far
 * more total attempts than available stock. The invariant under test is twofold:
 * <ol>
 *   <li>Exactly as many reservations succeed as there was initial stock — no more
 *       (no overselling) and no fewer (no lost updates / unnecessary rejections).</li>
 *   <li>The final {@code available} count is never negative and exactly accounts for
 *       every successful reservation.</li>
 * </ol>
 */
abstract class AbstractInventoryLedgerConcurrencyTest {

    protected abstract InventoryLedger createLedger();

    @Test
    @Timeout(30)
    void concurrentReservationsNeverOversellAvailableStock() throws InterruptedException {
        InventoryLedger ledger = createLedger();
        InventoryKey key = new InventoryKey("CONTENDED-SKU", "WH-HOT");
        int initialStock = 500;
        ledger.initialize(key, initialStock);

        int threadCount = 50;
        int attemptsPerThread = 40; // 2000 total attempts against only 500 units of stock
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientStockCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < attemptsPerThread; i++) {
                    try {
                        ledger.reserve(key, 1);
                        successCount.incrementAndGet();
                    } catch (InsufficientStockException expected) {
                        insufficientStockCount.incrementAndGet();
                    }
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> future : futures) {
            try {
                future.get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        executor.shutdown();

        assertEquals(threadCount * attemptsPerThread, successCount.get() + insufficientStockCount.get(),
                "every attempt must be accounted for as either a success or a rejection");
        assertEquals(initialStock, successCount.get(),
                "exactly the initial stock level of reservations must succeed - no overselling, no lost updates");

        InventoryLevel finalLevel = ledger.snapshot(key);
        assertEquals(0, finalLevel.getAvailable(), "all available stock must be exactly exhausted");
        assertEquals(initialStock, finalLevel.getReserved(), "every successful reservation must be reflected in reserved");
        assertTrue(finalLevel.getAvailable() >= 0, "available must never go negative under any strategy");
    }

    @Test
    @Timeout(30)
    void concurrentStockInAndReserveNeverLoseUpdates() throws InterruptedException {
        InventoryLedger ledger = createLedger();
        InventoryKey key = new InventoryKey("MIXED-SKU", "WH-HOT");
        ledger.initialize(key, 0);

        int writerThreads = 20;
        int stockInPerThread = 100; // total stock-in = 2000
        ExecutorService executor = Executors.newFixedThreadPool(writerThreads);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < writerThreads; t++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < stockInPerThread; i++) {
                    ledger.stockIn(key, 1);
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> future : futures) {
            try {
                future.get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        executor.shutdown();

        InventoryLevel finalLevel = ledger.snapshot(key);
        assertEquals(writerThreads * stockInPerThread, finalLevel.getAvailable(),
                "every concurrent stock-in increment must be reflected - no lost updates");
    }
}
