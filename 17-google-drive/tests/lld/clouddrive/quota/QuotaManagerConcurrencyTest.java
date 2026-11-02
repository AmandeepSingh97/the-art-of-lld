package lld.clouddrive.quota;

import lld.clouddrive.exception.QuotaExceededException;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency-correctness tests for {@link QuotaManager}: the exact race the README's
 * concurrency section calls out -- many threads for the SAME user racing to reserve quota must
 * never collectively be admitted past the limit, no matter how the thread scheduler interleaves
 * them.
 */
class QuotaManagerConcurrencyTest {

    @RepeatedTest(5) // race conditions are timing-sensitive; repeat to raise confidence.
    void concurrentReservationsBySameUserNeverExceedQuota() throws InterruptedException {
        long quota = 1_000L;
        long perRequestBytes = 100L; // exactly 10 requests worth of quota.
        int threadCount = 50; // far more contenders than quota allows, to force the race.

        QuotaManager quotaManager = new QuotaManager(quota);
        quotaManager.registerUser("race-user", quota);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    quotaManager.reserve("race-user", perRequestBytes);
                    accepted.incrementAndGet();
                } catch (QuotaExceededException expected) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown(); // release all threads at once to maximize contention.
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "threads did not finish in time");
        pool.shutdown();

        assertEquals(threadCount, accepted.get() + rejected.get(), "every thread must get exactly one outcome");
        assertEquals(10, accepted.get(), "exactly quota/perRequestBytes reservations should ever be admitted");
        assertEquals(quota, quotaManager.getUsedBytes("race-user"),
                "used bytes must land exactly on the quota boundary, never over and never under");
    }

    @Test
    void releaseNeverDrivesUsedBytesNegative() {
        QuotaManager quotaManager = new QuotaManager(1_000L);
        quotaManager.registerUser("u1", 1_000L);
        quotaManager.reserve("u1", 100L);
        quotaManager.release("u1", 500L); // release more than was ever reserved (defensive case).
        assertEquals(0, quotaManager.getUsedBytes("u1"));
    }

    @Test
    void reserveThrowsWithAccurateAvailableBytesWhenOverLimit() {
        QuotaManager quotaManager = new QuotaManager(1_000L);
        quotaManager.registerUser("u1", 1_000L);
        quotaManager.reserve("u1", 900L);

        QuotaExceededException ex = org.junit.jupiter.api.Assertions.assertThrows(
                QuotaExceededException.class, () -> quotaManager.reserve("u1", 200L));
        assertEquals(200L, ex.getRequestedBytes());
        assertEquals(100L, ex.getAvailableBytes());
    }
}
