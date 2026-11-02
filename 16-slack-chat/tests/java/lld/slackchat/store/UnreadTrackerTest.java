package lld.slackchat.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.UserId;
import org.junit.jupiter.api.Test;

class UnreadTrackerTest {

    private final ChannelId channelId = ChannelId.generate();
    private final UserId userId = UserId.generate();

    @Test
    void unreadCountIsLatestSequenceMinusLastRead() {
        UnreadTracker tracker = new UnreadTracker();

        assertEquals(10L, tracker.unreadCount(channelId, userId, 10L), "never read -> everything is unread");

        tracker.markRead(channelId, userId, 6L);
        assertEquals(4L, tracker.unreadCount(channelId, userId, 10L));
    }

    @Test
    void markReadNeverRegressesEvenIfCalledWithASmallerValueLater() {
        UnreadTracker tracker = new UnreadTracker();

        tracker.markRead(channelId, userId, 20L);
        tracker.markRead(channelId, userId, 5L); // e.g. a stale/out-of-order client report

        assertEquals(20L, tracker.lastRead(channelId, userId),
                "a late, smaller read position must not regress a user's progress");
    }

    @Test
    void concurrentMarkReadCallsConvergeToTheMaximumReportedPosition() throws InterruptedException {
        UnreadTracker tracker = new UnreadTracker();
        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 1; i <= threadCount; i++) {
            long sequence = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    tracker.markRead(channelId, userId, sequence);
                } catch (InterruptedException ignored) {
                    // test thread interrupted; latch handles accounting
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(threadCount, tracker.lastRead(channelId, userId),
                "concurrent reports of 1..N must converge on the maximum, N");
    }
}
