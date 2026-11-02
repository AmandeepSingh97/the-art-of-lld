package lld.logging.appender;

import lld.logging.core.LogEvent;
import lld.logging.core.LogLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncAppenderTest {

    @Test
    void preservesOrderingOfEventsFromASingleProducerThread() throws InterruptedException {
        RecordingAppender delegate = new RecordingAppender();
        AsyncAppender async = new AsyncAppender("async", delegate, 1024, BackpressurePolicy.BLOCK);
        try {
            for (int i = 0; i < 200; i++) {
                async.append(new LogEvent("x", LogLevel.INFO, "msg-" + i, null));
            }
        } finally {
            async.close(5000);
        }

        List<LogEvent> events = delegate.getEvents();
        assertEquals(200, events.size());
        for (int i = 0; i < 200; i++) {
            assertEquals("msg-" + i, events.get(i).getMessage(),
                    "single-producer order must be preserved end-to-end through the queue");
        }
    }

    @Test
    void blockPolicyNeverDropsEventsEvenWhenQueueIsSmallAndDelegateIsSlow() throws InterruptedException {
        AtomicInteger processed = new AtomicInteger();
        Appender slowDelegate = new DelayingAppender(5, processed);
        AsyncAppender async = new AsyncAppender("async-block", slowDelegate, 4, BackpressurePolicy.BLOCK);

        int total = 50;
        for (int i = 0; i < total; i++) {
            async.append(new LogEvent("x", LogLevel.INFO, "m" + i, null));
        }
        async.close(10_000);

        assertEquals(total, processed.get(), "BLOCK policy must never drop events");
        assertEquals(0, async.getDroppedEventCount());
    }

    @Test
    void dropNewPolicyDropsRatherThanBlocksWhenQueueIsFull() throws InterruptedException {
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        Appender blockedDelegate = new GatedAppender(releaseConsumer);
        // Capacity 2: first event is immediately taken by the consumer thread
        // and blocks there (gated), so the queue itself can hold at most 2
        // more before offer() starts failing.
        AsyncAppender async = new AsyncAppender("async-drop", blockedDelegate, 2, BackpressurePolicy.DROP_NEW);

        // Give the background thread a moment to take the first event and
        // block inside the gated delegate.
        async.append(new LogEvent("x", LogLevel.INFO, "first", null));
        Thread.sleep(200);

        int attempted = 20;
        for (int i = 0; i < attempted; i++) {
            async.append(new LogEvent("x", LogLevel.INFO, "overflow-" + i, null));
        }

        assertTrue(async.getDroppedEventCount() > 0, "queue should have overflowed and dropped some events");

        releaseConsumer.countDown();
        async.close(5000);
    }

    @Test
    void closeDrainsAllAlreadyQueuedEventsBeforeReturning() throws InterruptedException {
        RecordingAppender delegate = new RecordingAppender();
        AsyncAppender async = new AsyncAppender("async-drain", delegate, 8, BackpressurePolicy.BLOCK);

        for (int i = 0; i < 100; i++) {
            async.append(new LogEvent("x", LogLevel.INFO, "m" + i, null));
        }
        async.close(10_000);

        assertEquals(100, delegate.size(), "graceful close() must flush every event accepted before it was called");
        assertTrue(delegate.isClosed(), "close() must close the delegate appender too");
    }

    @Test
    void appendAfterCloseIsDroppedRatherThanThrowing() throws InterruptedException {
        RecordingAppender delegate = new RecordingAppender();
        AsyncAppender async = new AsyncAppender("async-postclose", delegate, 8, BackpressurePolicy.BLOCK);
        async.close(5000);

        async.append(new LogEvent("x", LogLevel.INFO, "too-late", null));

        assertEquals(0, delegate.size());
        assertEquals(1, async.getDroppedEventCount());
    }

    /** Test delegate that sleeps briefly per event, to simulate a slow sink. */
    private static final class DelayingAppender implements Appender {
        private final long delayMillis;
        private final AtomicInteger processed;

        DelayingAppender(long delayMillis, AtomicInteger processed) {
            this.delayMillis = delayMillis;
            this.processed = processed;
        }

        @Override
        public String getName() {
            return "delaying";
        }

        @Override
        public void append(LogEvent event) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            processed.incrementAndGet();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    /** Test delegate whose first append() blocks until released, to deterministically fill the queue. */
    private static final class GatedAppender implements Appender {
        private final CountDownLatch gate;
        private volatile boolean first = true;

        GatedAppender(CountDownLatch gate) {
            this.gate = gate;
        }

        @Override
        public String getName() {
            return "gated";
        }

        @Override
        public void append(LogEvent event) {
            if (first) {
                first = false;
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
