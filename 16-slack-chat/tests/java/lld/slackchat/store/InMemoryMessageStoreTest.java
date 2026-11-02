package lld.slackchat.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.Message;
import lld.slackchat.model.MessageId;
import lld.slackchat.model.UserId;
import lld.slackchat.pagination.MessagePage;
import lld.slackchat.pagination.PageRequest;
import org.junit.jupiter.api.Test;

class InMemoryMessageStoreTest {

    private final ChannelId channelId = ChannelId.generate();
    private final UserId sender = UserId.generate();

    @Test
    void appendAssignsStrictlyIncreasingSequenceNumbers() {
        InMemoryMessageStore store = new InMemoryMessageStore();

        Message first = store.append(channelId, sender, "one", java.util.Set.of());
        Message second = store.append(channelId, sender, "two", java.util.Set.of());
        Message third = store.append(channelId, sender, "three", java.util.Set.of());

        assertEquals(1L, first.sequence());
        assertEquals(2L, second.sequence());
        assertEquals(3L, third.sequence());
        assertEquals(3L, store.latestSequence(channelId));
    }

    @Test
    void findByIdReturnsSameMessageRegardlessOfChannelQuery() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        Message appended = store.append(channelId, sender, "hello", java.util.Set.of());

        MessageId id = appended.id();

        assertTrue(store.findById(id).isPresent());
        assertEquals(appended, store.findById(id).get());
    }

    @Test
    void backwardPaginationFromLatestReturnsNewestFirst() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        for (int i = 1; i <= 5; i++) {
            store.append(channelId, sender, "msg" + i, java.util.Set.of());
        }

        MessagePage page = store.getPage(channelId, PageRequest.latest(3));

        List<Long> sequences = page.messages().stream().map(Message::sequence).collect(Collectors.toList());
        assertEquals(List.of(5L, 4L, 3L), sequences);
        assertTrue(page.hasMore());
        assertEquals(3L, page.nextCursor().getAsLong());
    }

    @Test
    void backwardPaginationContinuesFromCursorWithoutDuplicatesOrGaps() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        for (int i = 1; i <= 5; i++) {
            store.append(channelId, sender, "msg" + i, java.util.Set.of());
        }

        MessagePage firstPage = store.getPage(channelId, PageRequest.latest(2));
        assertEquals(List.of(5L, 4L), seqOf(firstPage));

        MessagePage secondPage = store.getPage(channelId, PageRequest.before(firstPage.nextCursor().getAsLong(), 2));
        assertEquals(List.of(3L, 2L), seqOf(secondPage));
        assertTrue(secondPage.hasMore());

        MessagePage thirdPage = store.getPage(channelId, PageRequest.before(secondPage.nextCursor().getAsLong(), 2));
        assertEquals(List.of(1L), seqOf(thirdPage));
        assertFalse(thirdPage.hasMore());
    }

    @Test
    void forwardPaginationReturnsOldestFirstAfterCursor() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        for (int i = 1; i <= 4; i++) {
            store.append(channelId, sender, "msg" + i, java.util.Set.of());
        }

        MessagePage page = store.getPage(channelId, PageRequest.after(1L, 10));

        assertEquals(List.of(2L, 3L, 4L), seqOf(page));
        assertFalse(page.hasMore());
    }

    @Test
    void emptyChannelReturnsEmptyPage() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        MessagePage page = store.getPage(ChannelId.generate(), PageRequest.latest(10));
        assertEquals(0, page.messages().size());
        assertFalse(page.hasMore());
    }

    @Test
    void concurrentAppendsProduceUniqueSequenceNumbersWithNoLostWrites() throws InterruptedException {
        InMemoryMessageStore store = new InMemoryMessageStore();
        int threadCount = 16;
        int messagesPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < messagesPerThread; i++) {
                        store.append(channelId, sender, "concurrent-msg", java.util.Set.of());
                    }
                } catch (InterruptedException e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "all writer threads should finish within timeout");
        pool.shutdown();

        assertEquals(0, failures.get());
        int totalExpected = threadCount * messagesPerThread;
        assertEquals(totalExpected, store.latestSequence(channelId),
                "sequence counter must equal total messages with no gaps or duplicates under contention");

        List<Message> fullHistory = readEntireHistoryForward(store, channelId);
        assertEquals(totalExpected, fullHistory.size());
        List<Long> sequences = fullHistory.stream().map(Message::sequence).collect(Collectors.toList());
        assertEquals(sequences.stream().distinct().count(), sequences.size(), "no duplicate sequence numbers");
    }

    private static List<Long> seqOf(MessagePage page) {
        return page.messages().stream().map(Message::sequence).collect(Collectors.toList());
    }

    /** Pages forward (in capped batches of 500, the store's enforced maximum) to collect every message. */
    private static List<Message> readEntireHistoryForward(InMemoryMessageStore store, ChannelId channelId) {
        List<Message> all = new java.util.ArrayList<>();
        long cursor = 0L;
        while (true) {
            MessagePage page = store.getPage(channelId, PageRequest.after(cursor, 500));
            all.addAll(page.messages());
            if (!page.hasMore() || page.messages().isEmpty()) {
                break;
            }
            cursor = page.nextCursor().getAsLong();
        }
        return all;
    }
}
